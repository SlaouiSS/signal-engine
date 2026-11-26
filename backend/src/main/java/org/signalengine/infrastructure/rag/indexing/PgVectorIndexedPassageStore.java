package org.signalengine.infrastructure.rag.indexing;

import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.signalengine.application.persistence.FailedAttemptKind;
import org.signalengine.application.pipeline.RetryPolicy;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.indexing.AbandonedIndexing;
import org.signalengine.rag.indexing.ContentReplacement;
import org.signalengine.rag.indexing.IndexClaim;
import org.signalengine.rag.indexing.IndexedPassage;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.indexing.IndexingException;
import org.signalengine.rag.indexing.StaleIndexClaimException;
import org.signalengine.rag.provenance.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL/pgvector implementation of {@link IndexedPassageStore} (migrations V11 and V13).
 *
 * <p>{@link #claim} takes the exclusive right to index a content with one upsert on {@code
 * rag_content_index}: the claim is granted when no unexpired claim exists, and its {@code revision}
 * (a fencing token) grows with every grant. {@link #replaceContent} then runs as <b>one
 * transaction</b>: it first locks the content's row and rejects the call if the revision is no
 * longer the claim's, then upserts every passage and its embedding (keyed by the deterministic
 * passage id and the embedding model), removes the passages of the same content and chunker
 * configuration that the new result no longer contains, verifies that what is stored matches what
 * was written, and records the completion marker. Anything that fails rolls the whole thing back,
 * so a reader sees the previous complete index or the new one, never a mixture
 * (docs/adr/0018-pipeline-reliability.md).
 *
 * <p>Passages of <em>another</em> chunker configuration, and embeddings of another model, are never
 * touched (docs/adr/0012-pgvector-persistence-and-indexing.md Section 6). The vector is never
 * truncated or padded &mdash; a dimension other than the column's is a hard failure.
 *
 * <p>{@code JdbcClient} rather than a Spring Data JDBC repository, because the {@code ::vector}
 * cast and the {@code ON CONFLICT ... RETURNING} upsert do not map cleanly through {@code @Table}
 * records. No JPA/Hibernate.
 */
@Repository
class PgVectorIndexedPassageStore implements IndexedPassageStore {

  private static final Logger log = LoggerFactory.getLogger(PgVectorIndexedPassageStore.class);

  /** The fixed dimension of the {@code rag_passage_embedding.embedding} column (migration V11). */
  static final int COLUMN_DIMENSION = 768;

  private static final String IMPLEMENTATION_ID = "pgvector-indexed-passage-store";

  private static final String CLAIM_CONTENT =
      """
      INSERT INTO rag_content_index (
          content_id, revision, lease_until, attempted_fingerprint, attempted_chunker_id,
          attempted_chunker_version, created_at, updated_at)
      VALUES (
          :contentId, 1, :leaseUntil, :contentFingerprint, :chunkerId, :chunkerVersion, :now, :now)
      ON CONFLICT (content_id) DO UPDATE SET
          revision = rag_content_index.revision + 1,
          lease_until = :leaseUntil,
          -- Failures counted for another content or chunker configuration do not carry over.
          failed_attempts = CASE
              WHEN (rag_content_index.attempted_fingerprint, rag_content_index.attempted_chunker_id,
                    rag_content_index.attempted_chunker_version)
                   IS DISTINCT FROM (:contentFingerprint, :chunkerId, :chunkerVersion)
              THEN 0 ELSE rag_content_index.failed_attempts END,
          unexpected_failures = CASE
              WHEN (rag_content_index.attempted_fingerprint, rag_content_index.attempted_chunker_id,
                    rag_content_index.attempted_chunker_version)
                   IS DISTINCT FROM (:contentFingerprint, :chunkerId, :chunkerVersion)
              THEN 0 ELSE rag_content_index.unexpected_failures END,
          time_budget_overruns = CASE
              WHEN (rag_content_index.attempted_fingerprint, rag_content_index.attempted_chunker_id,
                    rag_content_index.attempted_chunker_version)
                   IS DISTINCT FROM (:contentFingerprint, :chunkerId, :chunkerVersion)
              THEN 0 ELSE rag_content_index.time_budget_overruns END,
          next_attempt_at = NULL,
          blocked_since = NULL,
          attempted_fingerprint = :contentFingerprint,
          attempted_chunker_id = :chunkerId,
          attempted_chunker_version = :chunkerVersion,
          updated_at = :now
      WHERE (rag_content_index.lease_until IS NULL OR rag_content_index.lease_until <= :now)
        AND (rag_content_index.indexed_at IS NULL
             OR rag_content_index.content_fingerprint <> :contentFingerprint
             OR rag_content_index.chunker_id <> :chunkerId
             OR rag_content_index.chunker_version <> :chunkerVersion)
      RETURNING revision
      """;

  private static final String LOCK_CLAIM =
      "SELECT revision FROM rag_content_index WHERE content_id = :contentId FOR UPDATE";

  private static final String UPSERT_PASSAGE =
      """
      INSERT INTO rag_passage (
          id, content_id, source_id, document_id, origin_uri, title,
          chunker_id, chunker_version, passage_ordinal, passage_text,
          provenance_attributes, metadata, updated_at)
      VALUES (
          :id, :contentId, :sourceId, :documentId, :originUri, :title,
          :chunkerId, :chunkerVersion, :ordinal, :text,
          CAST(:provenanceAttributes AS jsonb), CAST(:metadata AS jsonb), now())
      ON CONFLICT (id) DO UPDATE SET
          content_id = excluded.content_id,
          source_id = excluded.source_id,
          document_id = excluded.document_id,
          origin_uri = excluded.origin_uri,
          title = excluded.title,
          chunker_id = excluded.chunker_id,
          chunker_version = excluded.chunker_version,
          passage_ordinal = excluded.passage_ordinal,
          passage_text = excluded.passage_text,
          provenance_attributes = excluded.provenance_attributes,
          metadata = excluded.metadata,
          updated_at = now()
      """;

  private static final String UPSERT_EMBEDDING =
      """
      INSERT INTO rag_passage_embedding (
          passage_id, embedding_provider, embedding_model, embedding_model_version,
          embedding_dimension, embedding, updated_at)
      VALUES (
          :passageId, :provider, :model, :version, :dimension, CAST(:vector AS vector), now())
      ON CONFLICT (passage_id, embedding_model, embedding_model_version) DO UPDATE SET
          embedding_provider = excluded.embedding_provider,
          embedding_dimension = excluded.embedding_dimension,
          embedding = excluded.embedding,
          updated_at = now()
      RETURNING (xmax = 0) AS inserted
      """;

  /** Deleting a passage cascades to its embeddings (migration V11). */
  private static final String DELETE_STALE_PASSAGES =
      """
      DELETE FROM rag_passage
      WHERE content_id = :contentId
        AND chunker_id = :chunkerId
        AND chunker_version = :chunkerVersion
        AND id NOT IN (:keptPassageIds)
      """;

  private static final String COUNT_STORED_PASSAGES =
      """
      SELECT count(*)
      FROM rag_passage p
      JOIN rag_passage_embedding e ON e.passage_id = p.id
      WHERE p.content_id = :contentId
        AND p.chunker_id = :chunkerId
        AND p.chunker_version = :chunkerVersion
        AND e.embedding_provider = :provider
        AND e.embedding_model = :model
        AND e.embedding_model_version = :version
      """;

  private static final String RECORD_COMPLETION =
      """
      UPDATE rag_content_index SET
          indexed_at = :now,
          lease_until = NULL,
          failed_attempts = 0,
          unexpected_failures = 0,
          time_budget_overruns = 0,
          next_attempt_at = NULL,
          blocked_since = NULL,
          last_failure_reason = NULL,
          last_failure_recoverable = NULL,
          content_fingerprint = :contentFingerprint,
          chunker_id = :chunkerId,
          chunker_version = :chunkerVersion,
          embedding_provider = :provider,
          embedding_model = :model,
          embedding_model_version = :version,
          passage_count = :passageCount,
          updated_at = :now
      WHERE content_id = :contentId AND revision = :revision
      """;

  private static final String ABANDON_CLAIM =
      """
      UPDATE rag_content_index SET
          lease_until = NULL,
          updated_at = :now
      WHERE content_id = :contentId AND revision = :revision
      """;

  private static final String COUNT_FAILED_ABANDONMENT =
      """
      UPDATE rag_content_index SET
          lease_until = NULL,
          failed_attempts =
              failed_attempts + CASE WHEN :cause = 'FAILED_ATTEMPT' THEN 1 ELSE 0 END,
          unexpected_failures =
              unexpected_failures + CASE WHEN :cause = 'UNEXPECTED_ERROR' THEN 1 ELSE 0 END,
          time_budget_overruns =
              time_budget_overruns + CASE WHEN :cause = 'TIME_BUDGET_EXCEEDED' THEN 1 ELSE 0 END,
          last_failure_reason = :reason,
          last_failure_recoverable = :recoverable,
          updated_at = :now
      WHERE content_id = :contentId AND revision = :revision
      RETURNING CASE :cause
          WHEN 'FAILED_ATTEMPT' THEN failed_attempts
          WHEN 'UNEXPECTED_ERROR' THEN unexpected_failures
          ELSE time_budget_overruns END
      """;

  private static final String BLOCK_REJECTED_CONTENT =
      """
      UPDATE rag_content_index SET
          lease_until = NULL,
          next_attempt_at = NULL,
          blocked_since = :now,
          last_failure_reason = :reason,
          last_failure_recoverable = :recoverable,
          updated_at = :now
      WHERE content_id = :contentId AND revision = :revision
      """;

  private static final String SCHEDULE_NEXT_ATTEMPT =
      """
      UPDATE rag_content_index SET
          next_attempt_at = :nextAttemptAt,
          blocked_since = :blockedSince
      WHERE content_id = :contentId AND revision = :revision
      """;

  private final JdbcClient jdbcClient;
  private final Duration claimLease;
  private final RetryPolicy retryPolicy;
  private final int maxTimeBudgetOverruns;
  private final Clock clock;
  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Autowired
  PgVectorIndexedPassageStore(
      JdbcClient jdbcClient,
      RagIndexingProperties properties,
      @Qualifier("indexingRetryPolicy") RetryPolicy retryPolicy) {
    this(
        jdbcClient,
        properties.claimLease(),
        retryPolicy,
        properties.maxTimeBudgetOverruns(),
        Clock.systemUTC());
  }

  PgVectorIndexedPassageStore(
      JdbcClient jdbcClient,
      Duration claimLease,
      RetryPolicy retryPolicy,
      int maxTimeBudgetOverruns,
      Clock clock) {
    this.jdbcClient = jdbcClient;
    this.claimLease = claimLease;
    this.retryPolicy = retryPolicy;
    this.maxTimeBudgetOverruns = maxTimeBudgetOverruns;
    this.clock = clock;
  }

  @Override
  public org.signalengine.rag.ComponentDescriptor descriptor() {
    return new org.signalengine.rag.ComponentDescriptor(
        org.signalengine.rag.RagComponentType.INDEXED_PASSAGE_STORE,
        IMPLEMENTATION_ID,
        "v13;dim=" + COLUMN_DIMENSION);
  }

  @Override
  public Optional<IndexClaim> claim(
      String contentId, String contentFingerprint, ComponentDescriptor chunker) {
    Instant now = clock.instant();
    return jdbcClient
        .sql(CLAIM_CONTENT)
        .param("contentId", contentId)
        .param("contentFingerprint", contentFingerprint)
        .param("chunkerId", chunker.implementationId())
        .param("chunkerVersion", chunker.version())
        .param("now", utc(now))
        .param("leaseUntil", utc(now.plus(claimLease)))
        .query(Long.class)
        .optional()
        .map(revision -> new IndexClaim(contentId, revision));
  }

  @Override
  @Transactional
  public ContentReplacement replaceContent(
      IndexClaim claim, String contentFingerprint, List<IndexedPassage> passages) {
    requireConsistent(claim, contentFingerprint, passages);
    requireCurrent(claim);

    IndexedPassage first = passages.get(0);
    int inserted = 0;
    int updated = 0;
    for (IndexedPassage indexed : passages) {
      if (upsert(indexed)) {
        inserted++;
      } else {
        updated++;
      }
    }

    int removedStale =
        jdbcClient
            .sql(DELETE_STALE_PASSAGES)
            .param("contentId", claim.contentId())
            .param("chunkerId", first.chunker().implementationId())
            .param("chunkerVersion", first.chunker().version())
            .param("keptPassageIds", passages.stream().map(IndexedPassage::passageId).toList())
            .update();

    verifyStoredMatches(claim, first, passages.size());
    recordCompletion(claim, contentFingerprint, first, passages.size());
    return new ContentReplacement(inserted, updated, removedStale);
  }

  @Override
  @Transactional
  public void abandon(IndexClaim claim, AbandonedIndexing abandonment) {
    Instant now = clock.instant();
    if (abandonment.cause() == AbandonedIndexing.Cause.NOT_THE_CONTENT) {
      jdbcClient
          .sql(ABANDON_CLAIM)
          .param("contentId", claim.contentId())
          .param("revision", claim.revision())
          .param("now", utc(now))
          .update();
      return;
    }
    if (abandonment.cause() == AbandonedIndexing.Cause.CONTENT_REJECTED) {
      int blocked =
          jdbcClient
              .sql(BLOCK_REJECTED_CONTENT)
              .param("contentId", claim.contentId())
              .param("revision", claim.revision())
              .param("reason", abandonment.reason())
              .param("recoverable", abandonment.recoverable())
              .param("now", utc(now))
              .update();
      if (blocked == 1) {
        log.warn("Indexing of content {} is blocked: {}", claim.contentId(), abandonment.reason());
      }
      return;
    }
    Optional<Integer> failures =
        jdbcClient
            .sql(COUNT_FAILED_ABANDONMENT)
            .param("contentId", claim.contentId())
            .param("revision", claim.revision())
            .param("cause", abandonment.cause().name())
            .param("reason", abandonment.reason())
            .param("recoverable", abandonment.recoverable())
            .param("now", utc(now))
            .query(Integer.class)
            .optional();
    if (failures.isEmpty()) {
      return; // the claim was superseded
    }
    int limit =
        switch (abandonment.cause()) {
          case UNEXPECTED_ERROR -> retryPolicy.limitFor(FailedAttemptKind.UNEXPECTED);
          case TIME_BUDGET_EXCEEDED -> maxTimeBudgetOverruns;
          default -> retryPolicy.limitFor(FailedAttemptKind.TRANSIENT);
        };
    boolean exhausted = failures.get() >= limit;
    if (exhausted) {
      log.warn(
          "Indexing of content {} is blocked after {} failures: {}",
          claim.contentId(),
          failures.get(),
          abandonment.reason());
    }
    jdbcClient
        .sql(SCHEDULE_NEXT_ATTEMPT)
        .param("contentId", claim.contentId())
        .param("revision", claim.revision())
        .param(
            "nextAttemptAt",
            exhausted ? null : utc(now.plus(retryPolicy.delayAfter(failures.get()))),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .param("blockedSince", exhausted ? utc(now) : null, Types.TIMESTAMP_WITH_TIMEZONE)
        .update();
  }

  /** Locks the content's row and rejects the call if a newer claim superseded this one. */
  private void requireCurrent(IndexClaim claim) {
    Optional<Long> currentRevision =
        jdbcClient
            .sql(LOCK_CLAIM)
            .param("contentId", claim.contentId())
            .query(Long.class)
            .optional();
    if (currentRevision.isEmpty() || currentRevision.get() != claim.revision()) {
      throw new StaleIndexClaimException(claim);
    }
  }

  /** Inserts or refreshes one passage and its embedding; {@code true} if the embedding is new. */
  private boolean upsert(IndexedPassage indexed) {
    float[] vector = indexed.embedding();
    if (vector.length != COLUMN_DIMENSION) {
      throw new IndexingException(
          "embedding dimension "
              + vector.length
              + " is incompatible with this store's vector column ("
              + COLUMN_DIMENSION
              + "); a different-dimension model needs a new migration");
    }

    Passage passage = indexed.passage();
    Provenance provenance = passage.provenance();
    jdbcClient
        .sql(UPSERT_PASSAGE)
        .param("id", passage.passageId())
        .param("contentId", indexed.contentId())
        .param("sourceId", provenance.sourceId())
        .param("documentId", provenance.documentId())
        .param(
            "originUri", provenance.originUri() == null ? null : provenance.originUri().toString())
        .param("title", provenance.title())
        .param("chunkerId", indexed.chunker().implementationId())
        .param("chunkerVersion", indexed.chunker().version())
        .param("ordinal", passage.ordinal())
        .param("text", passage.text())
        .param("provenanceAttributes", toJson(provenance.attributes()))
        .param("metadata", toJson(passage.metadata()))
        .update();

    EmbeddingModelDescriptor model = indexed.embeddingModel();
    return Boolean.TRUE.equals(
        jdbcClient
            .sql(UPSERT_EMBEDDING)
            .param("passageId", passage.passageId())
            .param("provider", model.provider())
            .param("model", model.model())
            .param("version", model.version())
            .param("dimension", vector.length)
            .param("vector", toPgVectorLiteral(vector))
            .query(Boolean.class)
            .single());
  }

  /** The stored rows must be exactly the passages that were written, or nothing is committed. */
  private void verifyStoredMatches(IndexClaim claim, IndexedPassage first, int expectedCount) {
    Integer stored =
        jdbcClient
            .sql(COUNT_STORED_PASSAGES)
            .param("contentId", claim.contentId())
            .param("chunkerId", first.chunker().implementationId())
            .param("chunkerVersion", first.chunker().version())
            .param("provider", first.embeddingModel().provider())
            .param("model", first.embeddingModel().model())
            .param("version", first.embeddingModel().version())
            .query(Integer.class)
            .single();
    if (stored == null || stored != expectedCount) {
      throw new IndexingException(
          "content "
              + claim.contentId()
              + " would hold "
              + stored
              + " passages with embeddings after the replacement, expected "
              + expectedCount
              + "; nothing was committed");
    }
  }

  private void recordCompletion(
      IndexClaim claim, String contentFingerprint, IndexedPassage first, int passageCount) {
    int updatedRows =
        jdbcClient
            .sql(RECORD_COMPLETION)
            .param("contentId", claim.contentId())
            .param("revision", claim.revision())
            .param("now", utc(clock.instant()))
            .param("contentFingerprint", contentFingerprint)
            .param("chunkerId", first.chunker().implementationId())
            .param("chunkerVersion", first.chunker().version())
            .param("provider", first.embeddingModel().provider())
            .param("model", first.embeddingModel().model())
            .param("version", first.embeddingModel().version())
            .param("passageCount", passageCount)
            .update();
    if (updatedRows != 1) {
      throw new StaleIndexClaimException(claim);
    }
  }

  private static void requireConsistent(
      IndexClaim claim, String contentFingerprint, List<IndexedPassage> passages) {
    if (claim == null) {
      throw new IllegalArgumentException("claim must not be null");
    }
    if (contentFingerprint == null || contentFingerprint.isBlank()) {
      throw new IllegalArgumentException("contentFingerprint must not be blank");
    }
    if (passages == null || passages.isEmpty()) {
      throw new IndexingException("a replacement needs at least one passage");
    }
    IndexedPassage first = passages.get(0);
    for (IndexedPassage indexed : passages) {
      if (!claim.contentId().equals(indexed.contentId())) {
        throw new IndexingException(
            "passage " + indexed.passageId() + " does not belong to content " + claim.contentId());
      }
      if (!first.chunker().equals(indexed.chunker())
          || !first.embeddingModel().equals(indexed.embeddingModel())) {
        throw new IndexingException(
            "all passages of one replacement must share one chunker configuration and one"
                + " embedding model");
      }
    }
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private String toJson(Map<String, String> map) {
    try {
      return objectMapper.writeValueAsString(map);
    } catch (JacksonException notSerialisable) {
      throw new IndexingException("passage metadata is not serialisable to JSON", notSerialisable);
    }
  }

  private static String toPgVectorLiteral(float[] vector) {
    StringBuilder builder = new StringBuilder(vector.length * 12).append('[');
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(Float.toString(vector[i]));
    }
    return builder.append(']').toString();
  }
}
