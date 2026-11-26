package org.signalengine.infrastructure.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.indexing.ContentReplacement;
import org.signalengine.rag.indexing.IndexClaim;
import org.signalengine.rag.indexing.IndexedPassage;
import org.signalengine.rag.indexing.IndexingException;
import org.signalengine.rag.indexing.StaleIndexClaimException;
import org.signalengine.rag.provenance.Provenance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The reliability contract of the pgvector passage store against a real PostgreSQL
 * (docs/adr/0018-pipeline-reliability.md): a replacement is atomic, a claim is exclusive and
 * recoverable once its lease expires, a superseded run cannot overwrite a newer indexing, and the
 * completion marker is consistent with the passages and embeddings actually stored.
 */
class PgVectorIndexedPassageStoreReliabilityIntegrationTest
    extends AbstractPersistenceIntegrationTest {

  private static final Instant START = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration LEASE = Duration.ofMinutes(15);
  private static final ComponentDescriptor CHUNKER_V1 =
      new ComponentDescriptor(RagComponentType.CHUNKER, "structure-aware-chunker", "v1");
  private static final ComponentDescriptor CHUNKER_V2 =
      new ComponentDescriptor(RagComponentType.CHUNKER, "structure-aware-chunker", "v2");
  private static final EmbeddingModelDescriptor GEMMA =
      new EmbeddingModelDescriptor("ollama", "embeddinggemma", "embed/v1", 768);

  @Autowired private JdbcClient jdbcClient;

  private final MutableClock clock = new MutableClock(START);
  private final String contentId = "content-" + UUID.randomUUID();

  private PgVectorIndexedPassageStore store() {
    return new PgVectorIndexedPassageStore(jdbcClient, LEASE, clock);
  }

  private IndexedPassage passage(String suffix, ComponentDescriptor chunker) {
    String id = contentId + "-" + chunker.version() + "-" + suffix;
    Provenance provenance =
        new Provenance("src", null, "Title", "doc-" + contentId, id, Map.of("k", "v"));
    Passage passage = new Passage(id, 0, "text of " + id, provenance, Map.of());
    return new IndexedPassage(contentId, passage, chunker, vector(id.hashCode()), GEMMA);
  }

  private static float[] vector(int seed) {
    float[] vector = new float[768];
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) Math.sin((seed % 1000 + 1) * 0.001 * (i + 1));
    }
    return vector;
  }

  private int claimSequence;

  /** A claim for content that is not yet indexed as {@code fingerprint}: always grantable. */
  private IndexClaim claim(PgVectorIndexedPassageStore store) {
    return store.claim(contentId, "claimed-" + claimSequence++, CHUNKER_V1).orElseThrow();
  }

  private ContentReplacement replace(
      PgVectorIndexedPassageStore store, IndexClaim claim, IndexedPassage... passages) {
    return store.replaceContent(claim, "fingerprint-" + contentId, List.of(passages));
  }

  private List<String> storedPassageIds(ComponentDescriptor chunker) {
    return jdbcClient
        .sql(
            "SELECT id FROM rag_passage WHERE content_id = :contentId AND chunker_version = :version"
                + " ORDER BY id")
        .param("contentId", contentId)
        .param("version", chunker.version())
        .query(String.class)
        .list();
  }

  private int embeddingCount() {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM rag_passage_embedding e JOIN rag_passage p ON p.id = e.passage_id"
                + " WHERE p.content_id = :contentId")
        .param("contentId", contentId)
        .query(Integer.class)
        .single();
  }

  private Map<String, Object> marker() {
    return jdbcClient
        .sql("SELECT * FROM rag_content_index WHERE content_id = :contentId")
        .param("contentId", contentId)
        .query()
        .singleRow();
  }

  // --- atomic replacement -------------------------------------------------
  //
  // The two input-validation tests below fail before any write. That the transaction really rolls
  // back writes made earlier is shown by PgVectorIndexedPassageStoreTransactionIntegrationTest.

  @Test
  void aReplacementWhosePassagesDisagreeOnTheEmbeddingModelIsRejectedBeforeAnythingIsWritten() {
    PgVectorIndexedPassageStore store = store();
    IndexedPassage a = passage("a", CHUNKER_V1);
    IndexedPassage b = passage("b", CHUNKER_V1);
    IndexedPassage c = passage("c", CHUNKER_V1);
    replace(store, claim(store), a, b, c);
    Map<String, Object> markerBefore = marker();

    IndexedPassage d = passage("d", CHUNKER_V1);
    IndexedPassage wrongDimension =
        new IndexedPassage(
            contentId,
            passage("e", CHUNKER_V1).passage(),
            CHUNKER_V1,
            new float[8],
            new EmbeddingModelDescriptor("ollama", "embeddinggemma", "embed/v1", 8));
    IndexClaim second = claim(store);

    assertThatThrownBy(() -> replace(store, second, a, d, wrongDimension))
        .isInstanceOf(IndexingException.class);

    assertThat(storedPassageIds(CHUNKER_V1))
        .as("the previous index is exactly as it was: nothing inserted, nothing removed")
        .containsExactly(a.passageId(), b.passageId(), c.passageId());
    assertThat(embeddingCount()).isEqualTo(3);
    assertThat(marker().get("passage_count")).isEqualTo(markerBefore.get("passage_count"));
    assertThat(marker().get("indexed_at")).isEqualTo(markerBefore.get("indexed_at"));
  }

  @Test
  void aReplacementStoresTheNewPassagesAndRemovesTheStaleOnesOfTheSameChunkerConfiguration() {
    PgVectorIndexedPassageStore store = store();
    IndexedPassage a = passage("a", CHUNKER_V1);
    IndexedPassage b = passage("b", CHUNKER_V1);
    IndexedPassage c = passage("c", CHUNKER_V1);
    replace(store, claim(store), a, b, c);

    ContentReplacement replacement = replace(store, claim(store), a, b);

    assertThat(replacement.inserted()).isZero();
    assertThat(replacement.updated()).isEqualTo(2);
    assertThat(replacement.removedStale()).isEqualTo(1);
    assertThat(storedPassageIds(CHUNKER_V1)).containsExactly(a.passageId(), b.passageId());
    assertThat(embeddingCount()).as("the stale passage's embedding went with it").isEqualTo(2);
  }

  @Test
  void passagesOfAnotherChunkerConfigurationAreLeftAloneByAReplacement() {
    PgVectorIndexedPassageStore store = store();
    IndexedPassage v1 = passage("a", CHUNKER_V1);
    replace(store, claim(store), v1);

    replace(store, claim(store), passage("a", CHUNKER_V2));

    assertThat(storedPassageIds(CHUNKER_V1))
        .as("ADR 0012: another chunker configuration's passages coexist")
        .containsExactly(v1.passageId());
    assertThat(storedPassageIds(CHUNKER_V2)).hasSize(1);
  }

  // --- completion marker --------------------------------------------------

  @Test
  void theCompletionMarkerExistsOnlyAfterACommittedReplacementAndMatchesWhatWasStored() {
    PgVectorIndexedPassageStore store = store();
    IndexClaim claim = claim(store);
    assertThat(marker().get("indexed_at")).as("a claim alone is not an indexing").isNull();

    replace(store, claim, passage("a", CHUNKER_V1), passage("b", CHUNKER_V1));

    Map<String, Object> marker = marker();
    assertThat(marker.get("indexed_at")).isNotNull();
    assertThat(marker.get("content_fingerprint")).isEqualTo("fingerprint-" + contentId);
    assertThat(marker.get("chunker_id")).isEqualTo("structure-aware-chunker");
    assertThat(marker.get("chunker_version")).isEqualTo("v1");
    assertThat(marker.get("embedding_model")).isEqualTo("embeddinggemma");
    assertThat(marker.get("passage_count")).isEqualTo(2);
    assertThat(marker.get("passage_count"))
        .as("the marker's count equals the passages with embeddings really stored")
        .isEqualTo(embeddingCount());
    assertThat(marker.get("lease_until")).as("the claim is released").isNull();
    assertThat(marker.get("failed_attempts")).isEqualTo(0);
  }

  @Test
  void anEmbeddingOfTheWrongDimensionIsRejectedAndLeavesNoMarker() {
    PgVectorIndexedPassageStore store = store();
    IndexClaim claim = claim(store);
    IndexedPassage wrongDimension =
        new IndexedPassage(
            contentId,
            passage("a", CHUNKER_V1).passage(),
            CHUNKER_V1,
            new float[8],
            new EmbeddingModelDescriptor("ollama", "embeddinggemma", "embed/v1", 8));

    assertThatThrownBy(() -> replace(store, claim, wrongDimension))
        .isInstanceOf(IndexingException.class);

    assertThat(marker().get("indexed_at")).isNull();
    assertThat(storedPassageIds(CHUNKER_V1)).isEmpty();
  }

  // --- claim, lease, fencing ----------------------------------------------

  @Test
  void aContentCannotBeClaimedTwiceWhileTheLeaseIsValid() {
    PgVectorIndexedPassageStore store = store();

    Optional<IndexClaim> first = store.claim(contentId, "fp-1", CHUNKER_V1);
    Optional<IndexClaim> second = store.claim(contentId, "fp-1", CHUNKER_V1);

    assertThat(first).isPresent();
    assertThat(second).isEmpty();
  }

  @Test
  void aContentAlreadyCompletelyIndexedForTheSameFingerprintAndChunkerIsNotClaimedAgain() {
    PgVectorIndexedPassageStore store = store();
    IndexClaim claim = store.claim(contentId, "fp-1", CHUNKER_V1).orElseThrow();
    store.replaceContent(claim, "fp-1", List.of(passage("a", CHUNKER_V1)));

    assertThat(store.claim(contentId, "fp-1", CHUNKER_V1))
        .as("complete and current: nothing to do")
        .isEmpty();
  }

  @Test
  void aContentIndexedFromOtherContentOrAnotherChunkerConfigurationCanBeClaimedAgain() {
    PgVectorIndexedPassageStore store = store();
    IndexClaim claim = store.claim(contentId, "fp-1", CHUNKER_V1).orElseThrow();
    store.replaceContent(claim, "fp-1", List.of(passage("a", CHUNKER_V1)));

    Optional<IndexClaim> forOtherContent = store.claim(contentId, "fp-changed", CHUNKER_V1);
    assertThat(forOtherContent).as("outdated: built from other content").isPresent();
    store.abandon(forOtherContent.orElseThrow(), false);

    assertThat(store.claim(contentId, "fp-1", CHUNKER_V2))
        .as("outdated: built with another chunker configuration")
        .isPresent();
  }

  @Test
  void anExpiredLeaseIsRecoveredByANewClaimWithAHigherRevision() {
    PgVectorIndexedPassageStore store = store();
    IndexClaim abandonedByACrash = claim(store);
    clock.advance(LEASE.plusSeconds(1));

    IndexClaim recovered = claim(store);

    assertThat(recovered.revision()).isGreaterThan(abandonedByACrash.revision());
  }

  @Test
  void aSupersededRunCannotOverwriteANewerIndexing() {
    PgVectorIndexedPassageStore store = store();
    IndexClaim slowRun = claim(store);
    clock.advance(LEASE.plusSeconds(1));
    IndexClaim newerRun = claim(store);
    IndexedPassage newerPassage = passage("newer", CHUNKER_V1);
    replace(store, newerRun, newerPassage);

    assertThatThrownBy(() -> replace(store, slowRun, passage("stale", CHUNKER_V1)))
        .isInstanceOf(StaleIndexClaimException.class);

    assertThat(storedPassageIds(CHUNKER_V1))
        .as("the newer indexing is exactly what is stored")
        .containsExactly(newerPassage.passageId());
    assertThat(marker().get("passage_count")).isEqualTo(1);
  }

  @Test
  void aSupersededRunThatIsNotYetFinishedIsRejectedAtStoreTime() {
    PgVectorIndexedPassageStore store = store();
    IndexClaim slowRun = claim(store);
    clock.advance(LEASE.plusSeconds(1));
    claim(store); // a newer run takes over, and has not stored anything yet

    assertThatThrownBy(() -> replace(store, slowRun, passage("stale", CHUNKER_V1)))
        .isInstanceOf(StaleIndexClaimException.class);

    assertThat(storedPassageIds(CHUNKER_V1)).isEmpty();
  }

  @Test
  void ofManyConcurrentRunsExactlyOneGetsTheClaim() throws Exception {
    PgVectorIndexedPassageStore store = store();
    int runs = 8;
    ExecutorService pool = Executors.newFixedThreadPool(runs);
    try {
      CountDownLatch startTogether = new CountDownLatch(1);
      List<Future<Optional<IndexClaim>>> attempts = new ArrayList<>();
      for (int run = 0; run < runs; run++) {
        attempts.add(
            pool.submit(
                () -> {
                  startTogether.await();
                  return store.claim(contentId, "fp-concurrent", CHUNKER_V1);
                }));
      }
      startTogether.countDown();

      long granted = 0;
      for (Future<Optional<IndexClaim>> attempt : attempts) {
        if (attempt.get(20, TimeUnit.SECONDS).isPresent()) {
          granted++;
        }
      }
      assertThat(granted).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void anAbandonedClaimCanBeClaimedAgainAtOnceAndCountsTheFailedAttemptOnlyWhenAsked() {
    PgVectorIndexedPassageStore store = store();

    store.abandon(claim(store), true);
    store.abandon(claim(store), false);

    assertThat(marker().get("failed_attempts")).isEqualTo(1);
    assertThat(store.claim(contentId, "fp-after-abandon", CHUNKER_V1))
        .as("released, so claimable at once")
        .isPresent();
  }

  @Test
  void abandoningASupersededClaimChangesNothing() {
    PgVectorIndexedPassageStore store = store();
    IndexClaim slowRun = claim(store);
    clock.advance(LEASE.plusSeconds(1));
    IndexClaim newerRun = claim(store);

    store.abandon(slowRun, true);

    assertThat(marker().get("failed_attempts")).isEqualTo(0);
    assertThat(marker().get("lease_until")).as("the newer claim is still held").isNotNull();
    assertThat(newerRun.revision()).isEqualTo(slowRun.revision() + 1);
  }

  // --- test double ---------------------------------------------------------

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
      this.now = new AtomicReference<>(start);
    }

    void advance(Duration duration) {
      now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public Instant instant() {
      return now.get();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
