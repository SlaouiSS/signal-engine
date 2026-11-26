package org.signalengine.infrastructure.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.application.persistence.InterestRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.persistence.SummaryRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.application.pipeline.ItemRetryScheduler;
import org.signalengine.application.pipeline.RetryPolicy;
import org.signalengine.application.signal.DefaultProcessRelevantInformationUseCase;
import org.signalengine.application.signal.ImportanceAssessor;
import org.signalengine.application.signal.ImportanceAssessor.ImportanceVerdict;
import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.application.signal.ProcessRelevantInformationUseCase;
import org.signalengine.application.signal.RelevanceAssessor.RelevanceVerdict;
import org.signalengine.application.signal.SummaryGenerator.SummaryOutcome;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.RelevantInformation;
import org.signalengine.domain.Source;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.indexing.IndexingReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What the next pipeline run finds after a run that never finished, in the same application (no
 * restart), and what two runs at once produce, against a real PostgreSQL
 * (docs/adr/0019-pipeline-automation.md): the work is resumed from the last committed step and no
 * Signal or Summary is persisted twice. External calls may be repeated.
 */
class PipelineRecoveryIntegrationTest extends AbstractPersistenceIntegrationTest {

  /** Older than any item other tests leave behind, so these items head the queue. */
  private static final Instant OLDEST = Instant.parse("1980-01-01T00:00:00Z");

  @Autowired private SourceRepository sources;
  @Autowired private RawInformationItemRepository rawItems;
  @Autowired private RelevantInformationRepository relevantInformation;
  @Autowired private SignalRepository signals;
  @Autowired private SummaryRepository summaries;
  @Autowired private AreaOfInterestRepository areas;
  @Autowired private InterestRepository interests;
  @Autowired private ActivityRecordRepository activity;
  @Autowired private UnitOfWork unitOfWork;
  @Autowired private JdbcClient jdbcClient;

  private ProcessRelevantInformationUseCase useCase(ImportanceAssessor importance) {
    KnowledgeBaseIndexer indexer = mock(KnowledgeBaseIndexer.class);
    when(indexer.index(any(), any()))
        .thenReturn(
            new IndexingReport(
                "content",
                0,
                0,
                0,
                1,
                0,
                ComponentDescriptor.unspecified(RagComponentType.INDEXING_PIPELINE),
                List.of("not indexed in this test"),
                Map.of()));
    return new DefaultProcessRelevantInformationUseCase(
        relevantInformation,
        rawItems,
        signals,
        summaries,
        sources,
        areas,
        interests,
        query ->
            new RelevanceVerdict.Assessed(true, "relevant", Set.of("AI_AND_TECHNOLOGY"), Set.of()),
        importance,
        request -> new SummaryOutcome.Generated("summary", "notes"),
        indexer,
        activity,
        unitOfWork,
        new ItemRetryScheduler(
            rawItems,
            unitOfWork,
            new RetryPolicy(3, 5, Duration.ofHours(1), 2, Duration.ofHours(24), 0, () -> 0),
            Clock.systemUTC()),
        Clock.systemUTC(),
        500);
  }

  @Test
  void aRunThatDiesBetweenTwoStepsIsResumedByTheNextRunWithoutDuplicates() {
    RawInformationItem item = pendingItem();
    AtomicBoolean dies = new AtomicBoolean(true);
    ImportanceAssessor importance =
        query -> {
          if (dies.getAndSet(false)) {
            throw new SimulatedProcessDeath();
          }
          return new ImportanceVerdict.Assessed(true, "important");
        };

    assertThatThrownBy(() -> useCase(importance).process(item.relevantInformationId()))
        .isInstanceOf(SimulatedProcessDeath.class);
    assertThat(stateOf(item)).as("relevance stays committed").isEqualTo(ProcessingState.RELEVANT);

    useCase(importance).processPending();

    assertThat(stateOf(item)).isEqualTo(ProcessingState.SUMMARIZED);
    assertThat(signalCount(item)).isEqualTo(1);
    assertThat(summaryCount(item)).isEqualTo(1);
  }

  @Test
  void twoRunsAtOnceNeverPersistASecondSignalOrSummary() throws Exception {
    RawInformationItem item = pendingItem();
    CyclicBarrier bothAssessing = new CyclicBarrier(2);
    ImportanceAssessor importance =
        query -> {
          await(bothAssessing);
          return new ImportanceVerdict.Assessed(true, "important");
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          pool.submit(() -> useCase(importance).process(item.relevantInformationId()));
      Future<?> second =
          pool.submit(() -> useCase(importance).process(item.relevantInformationId()));
      first.get(60, TimeUnit.SECONDS);
      second.get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(signalCount(item)).isEqualTo(1);
    assertThat(summaryCount(item)).isEqualTo(1);
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (Exception notBothArrived) {
      // one run found the step already taken, so only the other assesses
    }
  }

  private RawInformationItem pendingItem() {
    UUID sourceId =
        sources
            .save(
                new Source(
                    null, "http", "Src", "https://ex.test/" + UUID.randomUUID(), true, null, null))
            .id();
    UUID relevantInformationId =
        relevantInformation
            .save(new RelevantInformation(null, null, Set.of(), Set.of(), null, null))
            .id();
    return rawItems.save(
        new RawInformationItem(
            null,
            sourceId,
            null,
            "hash-" + UUID.randomUUID(),
            "https://ex.test/a/" + UUID.randomUUID(),
            "raw",
            "story " + UUID.randomUUID(),
            null,
            null,
            OLDEST,
            new ProcessingState(ProcessingState.DEDUPLICATED, null, null, null, OLDEST),
            relevantInformationId,
            null,
            null));
  }

  private String stateOf(RawInformationItem item) {
    return rawItems.findById(item.id()).orElseThrow().processingState().state();
  }

  private int signalCount(RawInformationItem item) {
    return jdbcClient
        .sql("SELECT count(*) FROM signal WHERE relevant_information_id = :id")
        .param("id", item.relevantInformationId())
        .query(Integer.class)
        .single();
  }

  private int summaryCount(RawInformationItem item) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM summary m JOIN signal s ON s.id = m.signal_id"
                + " WHERE s.relevant_information_id = :id")
        .param("id", item.relevantInformationId())
        .query(Integer.class)
        .single();
  }

  /** Not a {@link RuntimeException}: nothing in the pipeline catches it, as with a killed JVM. */
  private static final class SimulatedProcessDeath extends Error {}
}
