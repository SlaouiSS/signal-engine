package org.signalengine.infrastructure.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiError;
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
import org.signalengine.application.signal.ImportanceAssessor.ImportanceVerdict;
import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.application.signal.RelevanceAssessor.RelevanceVerdict;
import org.signalengine.application.signal.SummaryGenerator.SummaryOutcome;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.RelevantInformation;
import org.signalengine.domain.Signal;
import org.signalengine.domain.SignalState;
import org.signalengine.domain.Source;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * More records waiting for a suspended capability than one batch holds, against the real paging
 * query: the records that need another capability are still processed in the same run.
 */
class SuspendedBacklogIntegrationTest extends AbstractPersistenceIntegrationTest {

  private static final int BATCH_LIMIT = 50;

  /** Older than any item other tests leave behind, so these items head their pages. */
  private static final Instant OLDEST = Instant.parse("1970-06-01T00:00:00Z");

  @Autowired private SourceRepository sources;
  @Autowired private RawInformationItemRepository rawItems;
  @Autowired private RelevantInformationRepository relevantInformation;
  @Autowired private SignalRepository signals;
  @Autowired private SummaryRepository summaries;
  @Autowired private AreaOfInterestRepository areas;
  @Autowired private InterestRepository interests;
  @Autowired private ActivityRecordRepository activity;
  @Autowired private UnitOfWork unitOfWork;

  private final List<RawInformationItem> blocked = new ArrayList<>();

  /** The shared database must not keep sixty pending records for other tests to process. */
  @AfterEach
  void retireTheBlockedBacklog() {
    blocked.forEach(
        item ->
            rawItems.transitionFromState(
                item.id(),
                ProcessingState.SIGNAL_CREATED,
                ProcessingState.summarized(Instant.now()),
                item.relevantInformationId()));
  }

  @Test
  void sixtyRecordsBlockedOnSummarisationDoNotKeepARecordFromRelevanceAssessment() {
    UUID sourceId =
        sources
            .save(
                new Source(
                    null, "http", "Src", "https://ex.test/" + UUID.randomUUID(), true, null, null))
            .id();
    for (int i = 0; i < 60; i++) {
      RawInformationItem item =
          item(sourceId, ProcessingState.SIGNAL_CREATED, OLDEST.plusSeconds(i));
      signals.save(new Signal(null, item.relevantInformationId(), SignalState.NEW, null, null));
      blocked.add(item);
    }
    RawInformationItem waitingForRelevance =
        item(sourceId, ProcessingState.DEDUPLICATED, OLDEST.plusSeconds(3600));

    useCaseWithSummarisationSuspended().processPending();

    assertThat(rawItems.findById(waitingForRelevance.id()).orElseThrow().processingState().state())
        .isEqualTo(ProcessingState.NOT_RELEVANT);
    assertThat(blocked)
        .allSatisfy(
            item ->
                assertThat(rawItems.findById(item.id()).orElseThrow().processingState().state())
                    .isEqualTo(ProcessingState.SIGNAL_CREATED));
  }

  private DefaultProcessRelevantInformationUseCase useCaseWithSummarisationSuspended() {
    AiError suspended =
        new AiError(
            AiError.CAPABILITY_SUSPENDED_CODE,
            AiError.SUSPENDED_CATEGORY,
            true,
            "capability 'summarize' is suspended",
            UUID.randomUUID(),
            Map.of());
    return new DefaultProcessRelevantInformationUseCase(
        relevantInformation,
        rawItems,
        signals,
        summaries,
        sources,
        areas,
        interests,
        query -> new RelevanceVerdict.Assessed(false, "off topic", Set.of(), Set.of()),
        query -> new ImportanceVerdict.Assessed(false, "routine"),
        request -> new SummaryOutcome.GenerationUnavailable(suspended),
        mock(KnowledgeBaseIndexer.class),
        activity,
        unitOfWork,
        new ItemRetryScheduler(
            rawItems,
            unitOfWork,
            new RetryPolicy(3, 5, Duration.ofHours(1), 2, Duration.ofHours(24), 0, () -> 0),
            Clock.systemUTC()),
        Clock.systemUTC(),
        BATCH_LIMIT);
  }

  private RawInformationItem item(UUID sourceId, String state, Instant waitingSince) {
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
            waitingSince,
            new ProcessingState(state, null, null, null, waitingSince),
            relevantInformationId,
            null,
            null));
  }
}
