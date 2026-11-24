package org.signalengine.infrastructure.rag.indexing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.indexing.ContentReplacement;
import org.signalengine.rag.indexing.IndexClaim;
import org.signalengine.rag.indexing.IndexedPassage;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.indexing.IndexingException;
import org.signalengine.rag.indexing.StaleIndexClaimException;
import org.signalengine.rag.provenance.Provenance;
import org.springframework.beans.factory.annotation.Autowired;
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

  /** The fixed dimension of the {@code rag_passage_embedding.embedding} column (migration V11). */
  static final int COLUMN_DIMENSION = 768;

  private static final String IMPLEMENTATION_ID = "pgvector-indexed-passage-store";

  private static final String CLAIM_CONTENT =
      """
      INSERT INTO rag_content_index (content_id, revision, lease_until, created_at, updated_at)
      VALUES (:contentId, 1, :leaseUntil, :now, :now)
      ON CONFLICT (content_id) DO UPDATE SET
          revision = rag_content_index.revision + 1,
          lease_until = :leaseUntil,
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
          failed_attempts = failed_attempts + CASE WHEN :countAsFailedAttempt THEN 1 ELSE 0 END,
          updated_at = :now
      WHERE content_id = :contentId AND revision = :revision
      """;

  private final JdbcClient jdbcClient;
  private final Duration claimLease;
  private final Clock clock;
  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Autowired
  PgVectorIndexedPassageStore(JdbcClient jdbcClient, RagIndexingProperties properties) {
    this(jdbcClient, properties.claimLease(), Clock.systemUTC());
  }

  PgVectorIndexedPassageStore(JdbcClient jdbcClient, Duration claimLease, Clock clock) {
    this.jdbcClient = jdbcClient;
    this.claimLease = claimLease;
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
  public void abandon(IndexClaim claim, boolean countAsFailedAttempt) {
    jdbcClient
        .sql(ABANDON_CLAIM)
        .param("contentId", claim.contentId())
        .param("revision", claim.revision())
        .param("countAsFailedAttempt", countAsFailedAttempt)
        .param("now", utc(clock.instant()))
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
