package org.signalengine.infrastructure.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityFailureException;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.signal.DefaultReconcileKnowledgeBaseIndexUseCase;
import org.signalengine.application.signal.IndexReconciliationReport;
import org.signalengine.application.signal.IndexReconciliationReport.Outcome;
import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.RelevantInformation;
import org.signalengine.domain.Source;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.chunking.Chunker;
import org.signalengine.rag.chunking.Chunking;
import org.signalengine.rag.chunking.StructureAwareChunker;
import org.signalengine.rag.embedding.EmbeddingException;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.indexing.IndexableContent;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.indexing.StagedIndexingPipeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reconciling the knowledge base index against a real PostgreSQL
 * (docs/adr/0018-pipeline-reliability.md): an item whose relevance is confirmed but whose indexing
 * was lost, interrupted, partial or outdated is indexed again — never assumed complete — without
 * duplicates, under concurrency, and with a bound on attempts.
 */
class KnowledgeBaseIndexReconciliationIntegrationTest extends AbstractPersistenceIntegrationTest {

  private static final int MAX_FAILED_ATTEMPTS = 3;
  private static final int BATCH_LIMIT = 500;

  @Autowired private SourceRepository sources;
  @Autowired private RawInformationItemRepository rawItems;
  @Autowired private RelevantInformationRepository relevantInformation;
  @Autowired private ActivityRecordRepository activity;
  @Autowired private IndexedPassageStore indexedPassageStore;
  @Autowired private StructureAwareChunker structureAwareChunker;
  @Autowired private JdbcClient jdbcClient;

  private final StubEmbeddingModel embedding = new StubEmbeddingModel();
  private final String marker = "reconcile-" + UUID.randomUUID();
  private UUID sourceId;
  private Chunker chunkerInUse;

  @BeforeEach
  void createSource() {
    chunkerInUse = structureAwareChunker;
    sourceId =
        sources
            .save(
                new Source(
                    null, "http", "Src", "https://ex.test/" + UUID.randomUUID(), true, null, null))
            .id();
  }

  // --- fixtures -------------------------------------------------------------

  private KnowledgeBaseIndexer indexer() {
    StagedIndexingPipeline pipeline =
        StagedIndexingPipeline.builder()
            .chunker(chunkerInUse)
            .embeddingModel(embedding)
            .store(indexedPassageStore)
            .failureCountsAsAttempt(
                failure ->
                    !AiCapabilityFailureException.findIn(failure)
                        .map(AiError::capabilitySuspended)
                        .orElse(false))
            .build();
    return new SignalEngineKnowledgeBaseIndexer(
        pipeline, new KnowledgeBaseIndexBacklog(jdbcClient, chunkerInUse));
  }

  private DefaultReconcileKnowledgeBaseIndexUseCase useCase() {
    return new DefaultReconcileKnowledgeBaseIndexUseCase(
        indexer(),
        rawItems,
        sources,
        activity,
        Clock.systemUTC(),
        MAX_FAILED_ATTEMPTS,
        BATCH_LIMIT);
  }

  /** An item whose relevance is confirmed, with a real content hash like collection produces. */
  private RawInformationItem relevantItem(String body) {
    UUID relevantInformationId =
        relevantInformation
            .save(new RelevantInformation(null, null, Set.of(), Set.of(), null, null))
            .id();
    String text = marker + " " + body;
    Instant now = Instant.now();
    return rawItems.save(
        new RawInformationItem(
            null,
            sourceId,
            null,
            sha256Hex(text),
            "https://ex.test/a/" + UUID.randomUUID(),
            text,
            text,
            null,
            null,
            now,
            ProcessingState.relevant(now),
            relevantInformationId,
            null,
            null));
  }

  private static String sha256Hex(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private IndexReconciliationReport reportFor(
      List<IndexReconciliationReport> reports, RawInformationItem item) {
    return reports.stream()
        .filter(report -> report.rawInformationItemId().equals(item.id()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no report for item " + item.id()));
  }

  private List<UUID> pendingItemIds() {
    return indexer().findItemIdsAwaitingIndexing(Instant.now(), MAX_FAILED_ATTEMPTS, BATCH_LIMIT);
  }

  /** The same chunker, announcing another configuration version. */
  private Chunker chunkerWithVersionSuffix(String suffix) {
    ComponentDescriptor current = structureAwareChunker.descriptor();
    return new Chunker() {
      @Override
      public Chunking chunk(IndexableContent content) {
        return structureAwareChunker.chunk(content);
      }

      @Override
      public ComponentDescriptor descriptor() {
        return new ComponentDescriptor(
            current.type(), current.implementationId(), current.version() + suffix);
      }
    };
  }

  /** A chunker that finds nothing to index in any content. */
  private Chunker chunkerThatProducesNoPassage() {
    ComponentDescriptor current = structureAwareChunker.descriptor();
    return new Chunker() {
      @Override
      public Chunking chunk(IndexableContent content) {
        return new Chunking(content.contentId(), List.of(), current, List.of(), Map.of());
      }

      @Override
      public ComponentDescriptor descriptor() {
        return current;
      }
    };
  }

  private int passageCount(RawInformationItem item) {
    return jdbcClient
        .sql("SELECT count(*) FROM rag_passage WHERE content_id = :id")
        .param("id", item.id().toString())
        .query(Integer.class)
        .single();
  }

  private Map<String, Object> contentIndex(RawInformationItem item) {
    return jdbcClient
        .sql("SELECT * FROM rag_content_index WHERE content_id = :id")
        .param("id", item.id().toString())
        .query()
        .singleRow();
  }

  // --- lost indexing ----------------------------------------------------------

  @Test
  void anItemWhoseIndexingFailedIsIndexedByALaterReconciliation() {
    RawInformationItem item = relevantItem("landmark regulation adopted today");
    embedding.failFor(marker, new EmbeddingException("embedding provider unreachable"));

    IndexReconciliationReport failed = reportFor(useCase().reconcilePending(), item);

    assertThat(failed.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(passageCount(item)).isZero();
    assertThat(pendingItemIds()).as("not lost: still pending").contains(item.id());
    assertThat(contentIndex(item).get("failed_attempts")).isEqualTo(1);

    embedding.recover(marker);
    IndexReconciliationReport indexed = reportFor(useCase().reconcilePending(), item);

    assertThat(indexed.outcome()).isEqualTo(Outcome.INDEXED);
    assertThat(passageCount(item)).isGreaterThan(0);
    assertThat(contentIndex(item).get("indexed_at")).isNotNull();
    assertThat(pendingItemIds())
        .as("complete and current: no longer pending")
        .doesNotContain(item.id());
  }

  @Test
  void aCompleteIndexingIsNeitherPendingNorReindexed() {
    RawInformationItem item = relevantItem("stable story");
    useCase().reconcilePending();
    int callsAfterFirstIndexing = embedding.callsFor(marker);
    int passagesAfterFirstIndexing = passageCount(item);

    List<IndexReconciliationReport> second = useCase().reconcilePending();
    List<IndexReconciliationReport> third = useCase().reconcilePending();

    assertThat(second).noneMatch(report -> report.rawInformationItemId().equals(item.id()));
    assertThat(third).noneMatch(report -> report.rawInformationItemId().equals(item.id()));
    assertThat(embedding.callsFor(marker)).isEqualTo(callsAfterFirstIndexing);
    assertThat(passageCount(item)).isEqualTo(passagesAfterFirstIndexing);
  }

  // --- uncertain old indexing --------------------------------------------------

  @Test
  void anOldIndexingWithoutAMarkerIsNotAssumedCompleteAndItsStalePassageIsReplaced() {
    RawInformationItem item = relevantItem("indexed before completion markers existed");
    ComponentDescriptor chunker = structureAwareChunker.descriptor();
    jdbcClient
        .sql(
            "INSERT INTO rag_passage (id, content_id, source_id, chunker_id, chunker_version,"
                + " passage_ordinal, passage_text)"
                + " VALUES (:id, :contentId, 'legacy-source', :chunkerId, :chunkerVersion, 0,"
                + " 'a passage a partial legacy indexing left behind')")
        .param("id", "legacy-" + item.id())
        .param("contentId", item.id().toString())
        .param("chunkerId", chunker.implementationId())
        .param("chunkerVersion", chunker.version())
        .update();

    assertThat(pendingItemIds())
        .as("having passages is not proof of completeness")
        .contains(item.id());
    IndexReconciliationReport report = reportFor(useCase().reconcilePending(), item);

    assertThat(report.outcome()).isEqualTo(Outcome.INDEXED);
    Integer legacyLeft =
        jdbcClient
            .sql("SELECT count(*) FROM rag_passage WHERE id = :id")
            .param("id", "legacy-" + item.id())
            .query(Integer.class)
            .single();
    assertThat(legacyLeft).as("the stale legacy passage was replaced atomically").isZero();
    assertThat(contentIndex(item).get("passage_count")).isEqualTo(passageCount(item));
  }

  @Test
  void anIndexingBuiltWithAnotherChunkerConfigurationIsOutdated() {
    RawInformationItem item = relevantItem("indexed with the current configuration");
    useCase().reconcilePending();
    List<UUID> pendingWithChangedConfiguration =
        new KnowledgeBaseIndexBacklog(jdbcClient, chunkerWithVersionSuffix("-changed"))
            .findItemIdsAwaitingIndexing(Instant.now(), MAX_FAILED_ATTEMPTS, BATCH_LIMIT);

    assertThat(pendingItemIds()).doesNotContain(item.id());
    assertThat(pendingWithChangedConfiguration).contains(item.id());
  }

  @Test
  void anIndexingWhoseContentFingerprintNoLongerMatchesTheItemIsOutdated() {
    RawInformationItem item = relevantItem("the content this was indexed from");
    useCase().reconcilePending();
    jdbcClient
        .sql(
            "UPDATE rag_content_index SET content_fingerprint = 'built-from-other-content'"
                + " WHERE content_id = :id")
        .param("id", item.id().toString())
        .update();

    assertThat(pendingItemIds()).contains(item.id());
    IndexReconciliationReport report = reportFor(useCase().reconcilePending(), item);

    assertThat(report.outcome()).isEqualTo(Outcome.INDEXED);
    assertThat(contentIndex(item).get("content_fingerprint")).isEqualTo(item.contentHash());
  }

  // --- nothing to index ---------------------------------------------------------------

  @Test
  void
      aContentThatYieldsNoPassageIsNeverReportedAsIndexedAndIsAttemptedOnlyABoundedNumberOfTimes() {
    chunkerInUse = chunkerThatProducesNoPassage();
    RawInformationItem item = relevantItem("content the chunker finds nothing to index in");

    IndexReconciliationReport report = reportFor(useCase().reconcilePending(), item);

    assertThat(report.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(contentIndex(item).get("indexed_at")).as("no completion marker").isNull();
    assertThat(passageCount(item)).isZero();

    for (int sweep = 0; sweep < MAX_FAILED_ATTEMPTS + 2; sweep++) {
      useCase().reconcilePending();
    }

    assertThat(contentIndex(item).get("failed_attempts")).isEqualTo(MAX_FAILED_ATTEMPTS);
    assertThat(pendingItemIds()).as("given up on, not retried forever").doesNotContain(item.id());
    Integer successRecords =
        jdbcClient
            .sql(
                "SELECT count(*) FROM activity_record WHERE raw_information_item_id = :id"
                    + " AND outcome = 'success'")
            .param("id", item.id())
            .query(Integer.class)
            .single();
    assertThat(successRecords).as("never announced as a success").isZero();
    assertThat(embedding.callsFor(marker)).as("nothing was embedded").isZero();
  }

  // --- interruption --------------------------------------------------------------

  @Test
  void aRunInterruptedAfterClaimingIsRecoveredOnceItsLeaseExpires() {
    RawInformationItem item = relevantItem("claimed then the process stopped");
    // the run that never finished
    indexedPassageStore.claim(
        item.id().toString(), item.contentHash(), structureAwareChunker.descriptor());

    assertThat(pendingItemIds()).as("another run is on it").doesNotContain(item.id());

    jdbcClient
        .sql(
            "UPDATE rag_content_index SET lease_until = now() - interval '1 minute'"
                + " WHERE content_id = :id")
        .param("id", item.id().toString())
        .update();

    assertThat(pendingItemIds()).as("the lease expired: recovered").contains(item.id());
    IndexReconciliationReport report = reportFor(useCase().reconcilePending(), item);
    assertThat(report.outcome()).isEqualTo(Outcome.INDEXED);
    assertThat(contentIndex(item).get("indexed_at")).isNotNull();
  }

  // --- bounded attempts ------------------------------------------------------------

  @Test
  void anItemThatKeepsFailingIsGivenUpAfterTheMaximumNumberOfAttempts() {
    RawInformationItem item = relevantItem("content the provider keeps rejecting");
    embedding.failFor(marker, new EmbeddingException("embedding provider rejected the content"));

    for (int sweep = 0; sweep < MAX_FAILED_ATTEMPTS + 3; sweep++) {
      useCase().reconcilePending();
    }

    assertThat(embedding.callsFor(marker)).isEqualTo(MAX_FAILED_ATTEMPTS);
    assertThat(contentIndex(item).get("failed_attempts")).isEqualTo(MAX_FAILED_ATTEMPTS);
    assertThat(pendingItemIds()).doesNotContain(item.id());
  }

  @Test
  void aSuspendedCapabilityStopsTheSweepAndDoesNotUseUpTheItemsAttempts() {
    RawInformationItem item = relevantItem("content waiting for a suspended capability");
    AiError suspended =
        new AiError(
            AiError.CAPABILITY_SUSPENDED_CODE,
            AiError.SUSPENDED_CATEGORY,
            true,
            "capability 'embed' is suspended",
            UUID.randomUUID(),
            Map.of());
    embedding.failFor(
        marker,
        new EmbeddingException("embed suspended", new AiCapabilityFailureException(suspended)));

    IndexReconciliationReport report = reportFor(useCase().reconcilePending(), item);

    assertThat(report.outcome()).isEqualTo(Outcome.SUSPENDED);
    assertThat(contentIndex(item).get("failed_attempts")).isEqualTo(0);
    assertThat(pendingItemIds()).as("untouched, so not lost").contains(item.id());
    List<String> messages =
        jdbcClient
            .sql(
                "SELECT message FROM activity_record WHERE category = 'knowledge_base_indexing'"
                    + " AND outcome = 'failure' AND raw_information_item_id IS NULL")
            .query(String.class)
            .list();
    assertThat(messages).anyMatch(message -> message.contains("suspended"));
  }

  // --- concurrency ---------------------------------------------------------------

  @Test
  void twoConcurrentReconciliationsIndexEachItemExactlyOnce() throws Exception {
    List<RawInformationItem> items = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      items.add(relevantItem("concurrent story number " + i));
    }
    embedding.slowDown(Duration.ofMillis(300));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch startTogether = new CountDownLatch(1);
      List<Future<List<IndexReconciliationReport>>> runs = new ArrayList<>();
      for (int run = 0; run < 2; run++) {
        runs.add(
            pool.submit(
                () -> {
                  startTogether.await();
                  return useCase().reconcilePending();
                }));
      }
      startTogether.countDown();
      for (Future<List<IndexReconciliationReport>> run : runs) {
        run.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(embedding.callsFor(marker))
        .as("one embedding call per item: nobody indexed an item twice")
        .isEqualTo(items.size());
    for (RawInformationItem item : items) {
      assertThat(contentIndex(item).get("passage_count")).isEqualTo(passageCount(item));
      assertThat(contentIndex(item).get("indexed_at")).isNotNull();
    }
    assertThat(pendingItemIds())
        .doesNotContainAnyElementsOf(items.stream().map(RawInformationItem::id).toList());
  }

  // --- stub ------------------------------------------------------------------------

  /** A deterministic 768-d stub; failures and delays can be scripted per item marker. */
  private static final class StubEmbeddingModel implements EmbeddingModel {
    private final Map<String, RuntimeException> failureByMarker = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> callsByMarker = new ConcurrentHashMap<>();
    private volatile Duration delay = Duration.ZERO;

    void failFor(String marker, RuntimeException failure) {
      failureByMarker.put(marker, failure);
    }

    void recover(String marker) {
      failureByMarker.remove(marker);
    }

    void slowDown(Duration answerAfter) {
      this.delay = answerAfter;
    }

    int callsFor(String marker) {
      return callsByMarker.getOrDefault(marker, new AtomicInteger()).get();
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
      for (String text : request.texts()) {
        for (String marker : failureByMarker.keySet()) {
          if (text.contains(marker)) {
            callsByMarker.computeIfAbsent(marker, ignored -> new AtomicInteger()).incrementAndGet();
            throw failureByMarker.get(marker);
          }
        }
      }
      for (String marker : knownMarkers(request)) {
        callsByMarker.computeIfAbsent(marker, ignored -> new AtomicInteger()).incrementAndGet();
      }
      if (!delay.isZero()) {
        try {
          Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      List<float[]> vectors = new ArrayList<>();
      for (String text : request.texts()) {
        float[] vector = new float[768];
        int hash = text.hashCode();
        for (int i = 0; i < vector.length; i++) {
          vector[i] = (float) Math.cos((hash + i) * 0.01);
        }
        vectors.add(vector);
      }
      return new EmbeddingResult(
          vectors, 768, new EmbeddingModelDescriptor("stub", "stub-768", "test", 768));
    }

    /** The markers of this test instance that appear in the request. */
    private Set<String> knownMarkers(EmbeddingRequest request) {
      Set<String> found = new java.util.HashSet<>();
      for (String text : request.texts()) {
        int start = text.indexOf("reconcile-");
        if (start >= 0) {
          found.add(
              text.substring(start, Math.min(text.length(), start + "reconcile-".length() + 36)));
        }
      }
      return found;
    }
  }
}
