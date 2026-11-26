package org.signalengine.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.ai.AiResponseMetadata;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.application.persistence.FailedAttemptKind;
import org.signalengine.application.persistence.InterestRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.persistence.SummaryRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.application.pipeline.DefaultRequeueExhaustedWorkUseCase;
import org.signalengine.application.pipeline.ExhaustedWork;
import org.signalengine.application.pipeline.ItemRetryScheduler;
import org.signalengine.application.pipeline.RequeueExhaustedWorkUseCase;
import org.signalengine.application.pipeline.RetryPolicy;
import org.signalengine.application.signal.DefaultProcessRelevantInformationUseCase;
import org.signalengine.application.signal.DefaultRelevanceAssessor;
import org.signalengine.application.signal.ImportanceAssessor.ImportanceVerdict;
import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.application.signal.RelevanceAssessor;
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
 * The retry guarantees against a real PostgreSQL, through the real processing use case and circuit
 * breaker (docs/adr/0019-pipeline-automation.md): an outage, however long, uses at most two item
 * attempts of a capability; it never fails an item permanently; whatever is set aside can be
 * requeued; and a permanent failure is never requeued.
 */
class PipelineOutageRetryIntegrationTest extends AbstractPersistenceIntegrationTest {

  private static final Instant START = Instant.parse("2026-07-01T10:00:00Z");

  /** Older than any item other tests leave behind, so these items head the queue. */
  private static final Instant OLDEST = Instant.parse("1990-01-01T00:00:00Z");

  private static final AiCapabilitySuspensionProperties SUSPENSION =
      new AiCapabilitySuspensionProperties(
          Duration.ofMinutes(15),
          Duration.ofHours(6),
          Duration.ofMinutes(6),
          Duration.ofHours(24),
          3,
          Duration.ofSeconds(30),
          Duration.ofMinutes(5));

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

  private final MutableClock clock = new MutableClock(START);
  private final String capability = "relevance-" + UUID.randomUUID();
  private final OutageProvider provider = new OutageProvider();

  @Test
  void aDayLongOutageUsesAtMostTwoAttemptsAndEveryItemSucceedsAfterwards() {
    List<RawInformationItem> items = pendingItems(10);
    provider.down = true;

    for (int cycle = 0; cycle < 24; cycle++) {
      useCase().processPending();
      clock.advance(Duration.ofHours(1));
    }

    assertThat(items)
        .allSatisfy(item -> assertThat(stateOf(item)).isEqualTo(ProcessingState.DEDUPLICATED));
    assertThat(attemptsUsedBy(items)).isLessThanOrEqualTo(2);

    provider.down = false;
    clock.advance(Duration.ofDays(1));
    useCase().processPending();

    assertThat(items)
        .allSatisfy(item -> assertThat(stateOf(item)).isEqualTo(ProcessingState.NOT_RELEVANT));
  }

  @Test
  void anItemSetAsideDuringAnOutageIsRetryableAndTheOperatorActionRequeuesItOnce() {
    RawInformationItem nearlyExhausted = pendingItems(1).get(0);
    for (int attempt = 0; attempt < 2; attempt++) {
      rawItems.countFailedAttempt(
          nearlyExhausted.id(), ProcessingState.DEDUPLICATED, FailedAttemptKind.TRANSIENT);
    }
    RawInformationItem permanent = pendingItems(1).get(0);
    rawItems.transitionFromState(
        permanent.id(),
        ProcessingState.DEDUPLICATED,
        ProcessingState.failed("relevance_assessment", "invalid output", false, clock.instant()),
        permanent.relevantInformationId());
    provider.down = true;

    useCase().processPending();

    RawInformationItem setAside = rawItems.findById(nearlyExhausted.id()).orElseThrow();
    assertThat(setAside.processingState().state()).isEqualTo(ProcessingState.FAILED);
    assertThat(setAside.processingState().failureRetryable()).isTrue();
    assertThat(setAside.normalizedContent()).isEqualTo(nearlyExhausted.normalizedContent());

    List<ExhaustedWork> requeued = requeue().requeueRecoverableWork();

    assertThat(requeued)
        .extracting(ExhaustedWork::rawInformationItemId)
        .contains(nearlyExhausted.id())
        .doesNotContain(permanent.id());
    assertThat(stateOf(nearlyExhausted)).isEqualTo(ProcessingState.DEDUPLICATED);
    assertThat(attemptsUsedBy(List.of(nearlyExhausted))).isZero();
    assertThat(stateOf(permanent)).isEqualTo(ProcessingState.FAILED);
    assertThat(requeue().requeueRecoverableWork())
        .extracting(ExhaustedWork::rawInformationItemId)
        .doesNotContain(nearlyExhausted.id());
    assertThat(auditRecords(nearlyExhausted)).isEqualTo(1);
  }

  @Test
  void anItemTheAiServiceAlwaysRejectsAsInvalidDoesNotHoldBackValidItemsAndIsSetAside() {
    provider.alwaysInvalidText = "TOO-LARGE-" + UUID.randomUUID();
    RawInformationItem rejected = itemWaitingSince(provider.alwaysInvalidText, OLDEST);
    List<RawInformationItem> valid = new ArrayList<>();
    List<Integer> validStillPendingAfterEachCycle = new ArrayList<>();

    for (int cycle = 0; cycle < 12; cycle++) {
      valid.add(itemWaitingSince("valid story " + cycle, clock.instant()));
      useCase().processPending();
      validStillPendingAfterEachCycle.add(
          (int)
              valid.stream()
                  .filter(item -> stateOf(item).equals(ProcessingState.DEDUPLICATED))
                  .count());
      clock.advance(Duration.ofHours(1));
    }

    assertThat(stateOf(rejected))
        .as(
            "set aside; valid items still pending after each cycle: %s",
            validStillPendingAfterEachCycle)
        .isNotEqualTo(ProcessingState.DEDUPLICATED);
    assertThat(validStillPendingAfterEachCycle)
        .as("every valid item is processed in the cycle in which it arrives")
        .containsOnly(0);
  }

  @Test
  void contentOverTheRelevanceLimitIsSetAsideWholeWithoutAnyCallAndCanBeRequeued() {
    String oversized = "y".repeat(20_001);
    RawInformationItem item = itemWaitingSince(oversized, OLDEST.minusSeconds(60));
    AtomicInteger calls = new AtomicInteger();
    AiCapabilityInvoker counting =
        new AiCapabilityInvoker() {
          @Override
          public <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> type) {
            calls.incrementAndGet();
            return provider.invoke(request, type);
          }
        };

    var verdict =
        new DefaultRelevanceAssessor(counting)
            .assess(new RelevanceAssessor.Query(oversized, List.of(), List.of()));
    useCaseWith(
            query ->
                new DefaultRelevanceAssessor(counting)
                    .assess(new RelevanceAssessor.Query(query.itemText(), List.of(), List.of())))
        .process(item.relevantInformationId());

    assertThat(verdict).isInstanceOf(RelevanceVerdict.AssessmentUnavailable.class);
    assertThat(calls).hasValue(0);
    RawInformationItem setAside = rawItems.findById(item.id()).orElseThrow();
    assertThat(setAside.processingState().state()).isEqualTo(ProcessingState.FAILED);
    assertThat(setAside.processingState().failureRetryable()).isTrue();
    assertThat(setAside.processingState().failureReason())
        .contains("20001 characters", "at most 20000");
    assertThat(setAside.normalizedContent()).as("kept whole").isEqualTo(oversized);
    assertThat(requeue().requeueRecoverableWork())
        .extracting(ExhaustedWork::rawInformationItemId)
        .contains(item.id());
  }

  private RawInformationItem itemWaitingSince(String text, Instant waitingSince) {
    RawInformationItem template = pendingItems(1).get(0);
    jdbcClient
        .sql(
            "UPDATE raw_information_item SET normalized_content = :text,"
                + " processing_updated_at = :since WHERE id = :id")
        .param("text", text)
        .param("since", java.time.OffsetDateTime.ofInstant(waitingSince, ZoneOffset.UTC))
        .param("id", template.id())
        .update();
    return rawItems.findById(template.id()).orElseThrow();
  }

  private RequeueExhaustedWorkUseCase requeue() {
    return new DefaultRequeueExhaustedWorkUseCase(
        rawItems, mock(KnowledgeBaseIndexer.class), activity, unitOfWork, clock);
  }

  private DefaultProcessRelevantInformationUseCase useCase() {
    AiCapabilityInvoker invoker =
        new SuspendingAiCapabilityInvoker(
            provider, new AiCapabilitySuspensionStore(jdbcClient), SUSPENSION, clock);
    RelevanceAssessor relevance =
        query ->
            switch (invoker.invoke(
                AiCapabilityRequest.of(capability, 1, query.itemText()), String.class)) {
              case Produced<String> produced ->
                  new RelevanceVerdict.Assessed(false, "off topic", Set.of(), Set.of());
              case Failed<String> failed ->
                  new RelevanceVerdict.AssessmentUnavailable(failed.error());
            };
    return useCaseWith(relevance);
  }

  private DefaultProcessRelevantInformationUseCase useCaseWith(RelevanceAssessor relevance) {
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
        relevance,
        query -> new ImportanceVerdict.Assessed(false, "routine"),
        request -> new SummaryOutcome.Generated("summary", "notes"),
        indexer,
        activity,
        unitOfWork,
        new ItemRetryScheduler(
            rawItems,
            unitOfWork,
            new RetryPolicy(3, 5, Duration.ofHours(1), 2, Duration.ofHours(24), 0, () -> 0),
            clock),
        clock,
        500);
  }

  private List<RawInformationItem> pendingItems(int count) {
    UUID sourceId =
        sources
            .save(
                new Source(
                    null, "http", "Src", "https://ex.test/" + UUID.randomUUID(), true, null, null))
            .id();
    List<RawInformationItem> items = new ArrayList<>();
    for (int position = 0; position < count; position++) {
      UUID relevantInformationId =
          relevantInformation
              .save(new RelevantInformation(null, null, Set.of(), Set.of(), null, null))
              .id();
      Instant waitingSince = OLDEST.plusSeconds(position);
      items.add(
          rawItems.save(
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
                  new ProcessingState(ProcessingState.DEDUPLICATED, null, null, null, waitingSince),
                  relevantInformationId,
                  null,
                  null)));
    }
    return items;
  }

  private String stateOf(RawInformationItem item) {
    return rawItems.findById(item.id()).orElseThrow().processingState().state();
  }

  private int attemptsUsedBy(List<RawInformationItem> items) {
    return jdbcClient
        .sql("SELECT sum(transient_failures) FROM raw_information_item WHERE id IN (:ids)")
        .param("ids", items.stream().map(RawInformationItem::id).toList())
        .query(Integer.class)
        .single();
  }

  private int auditRecords(RawInformationItem item) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM activity_record WHERE category = 'operations'"
                + " AND raw_information_item_id = :id")
        .param("id", item.id())
        .query(Integer.class)
        .single();
  }

  /** A provider that is unreachable while {@code down}. */
  private static final class OutageProvider implements AiCapabilityInvoker {
    volatile boolean down;

    /** Content the AI service rejects every time, as it does a text over its size limit. */
    volatile String alwaysInvalidText;

    @Override
    @SuppressWarnings("unchecked")
    public <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> resultType) {
      if (String.valueOf(request.payload()).equals(alwaysInvalidText)) {
        return new Failed<>(
            new AiError(
                "AI_REQUEST_INVALID",
                "request",
                false,
                "relevance payload is invalid",
                request.correlationId(),
                Map.of()));
      }
      if (down) {
        return new Failed<>(
            new AiError(
                "AI_TRANSPORT_ERROR",
                "transport",
                true,
                "the AI service is unreachable",
                request.correlationId(),
                Map.of()));
      }
      return new Produced<>(
          (R) "ok", new AiResponseMetadata("test", "test", "test/v1", 1L), request.correlationId());
    }
  }

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
