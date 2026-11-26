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
import org.signalengine.application.persistence.InterestRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.persistence.SummaryRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.application.signal.DefaultProcessRelevantInformationUseCase;
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
 * What a provider that rejects requests does to a batch of items, through the real processing use
 * case, the real suspension and a real PostgreSQL (docs/adr/0018-pipeline-reliability.md).
 *
 * <p>A permanent HTTP 4xx such as 400 or 422 is ambiguous. It is what one oversized item gets, and
 * it is also what <em>every</em> item gets when a model parameter is not accepted. Before this
 * mission every such rejection marked its item {@code FAILED}, for good, so a global mistake could
 * turn a whole batch into permanent failures. The tests below pin the behaviour that replaces it:
 * items are preserved while the cause is uncertain, a genuinely bad item is failed once the
 * capability is known to work, and nothing is retried forever.
 */
class ProviderRejectionPipelineIntegrationTest extends AbstractPersistenceIntegrationTest {

  private static final Instant START = Instant.parse("2026-06-01T10:00:00Z");

  /** Older than any item other tests leave behind, so these items head the queue. */
  private static final Instant OLDEST = Instant.parse("2000-01-01T00:00:00Z");

  private static final AiCapabilitySuspensionProperties PROPERTIES =
      new AiCapabilitySuspensionProperties(
          Duration.ofMinutes(10),
          Duration.ofHours(1),
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
  private final ProviderDouble provider = new ProviderDouble();

  // --- the scenarios ------------------------------------------------------------

  @Test
  void aGlobalRejectionDoesNotTurnAWholeBatchIntoPermanentFailures() {
    provider.rejectEveryRequest = true;
    List<RawInformationItem> batch = pendingItems("story one", "story two", "story three");

    useCase().processPending();

    assertThat(batch)
        .allSatisfy(item -> assertThat(stateOf(item)).isEqualTo(ProcessingState.DEDUPLICATED));
    assertThat(provider.calls.get())
        .as("one call proved the rejection; the others were not even attempted")
        .isEqualTo(1);
    assertThat(activityMessages()).anyMatch(message -> message.contains("suspended capability"));
  }

  @Test
  void aGenuinelyOversizedItemIsFailedOnceTheCapabilityIsKnownToWork() {
    List<RawInformationItem> batch = pendingItems("story one", "POISON story", "story three");

    useCase().processPending();

    assertThat(stateOf(batch.get(0)))
        .as("handled before the bad one")
        .isNotEqualTo(ProcessingState.DEDUPLICATED);
    assertThat(stateOf(batch.get(1))).as("the bad item itself").isEqualTo(ProcessingState.FAILED);
    assertThat(stateOf(batch.get(2)))
        .as("not collateral damage")
        .isNotEqualTo(ProcessingState.DEDUPLICATED);
    assertThat(provider.calls.get()).isEqualTo(3);
  }

  @Test
  void aBadItemAtTheHeadOfAFreshQueueDoesNotBlockTheQueueAndIsNotLost() {
    List<RawInformationItem> batch = pendingItems("POISON story", "story two", "story three");

    useCase().processPending();

    assertThat(batch)
        .as(
            "first sweep: the cause is unknown, so nothing is failed and nothing is attempted twice")
        .allSatisfy(item -> assertThat(stateOf(item)).isEqualTo(ProcessingState.DEDUPLICATED));
    assertThat(provider.calls.get()).isEqualTo(1);

    clock.advance(Duration.ofMinutes(10));
    useCase().processPending();

    assertThat(stateOf(batch.get(1)))
        .as("the others probe and progress")
        .isNotEqualTo(ProcessingState.DEDUPLICATED);
    assertThat(stateOf(batch.get(2))).isNotEqualTo(ProcessingState.DEDUPLICATED);
    assertThat(stateOf(batch.get(0)))
        .as("the bad item, now known to be bad, is failed once — not retried forever")
        .isEqualTo(ProcessingState.FAILED);
    assertThat(provider.calls.get()).as("1 first sweep + 3 second sweep").isEqualTo(4);
  }

  // --- fixtures -------------------------------------------------------------------

  private DefaultProcessRelevantInformationUseCase useCase() {
    AiCapabilityInvoker invoker =
        new SuspendingAiCapabilityInvoker(
            provider, new AiCapabilitySuspensionStore(jdbcClient), PROPERTIES, clock);
    RelevanceAssessor relevance =
        query ->
            switch (invoker.invoke(
                AiCapabilityRequest.of(capability, 1, query.itemText()), String.class)) {
              case Produced<String> produced ->
                  new RelevanceVerdict.Assessed(
                      true, "relevant", Set.of("AI_AND_TECHNOLOGY"), Set.of());
              case Failed<String> failed ->
                  new RelevanceVerdict.AssessmentUnavailable(failed.error());
            };
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
        clock,
        500);
  }

  /** Items awaiting relevance assessment, oldest first, in the order given. */
  private List<RawInformationItem> pendingItems(String... texts) {
    UUID sourceId =
        sources
            .save(
                new Source(
                    null, "http", "Src", "https://ex.test/" + UUID.randomUUID(), true, null, null))
            .id();
    List<RawInformationItem> items = new ArrayList<>();
    for (int position = 0; position < texts.length; position++) {
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
                  "raw " + texts[position],
                  texts[position] + " " + UUID.randomUUID(),
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

  private List<String> activityMessages() {
    return jdbcClient
        .sql("SELECT message FROM activity_record WHERE category = 'signal_pipeline'")
        .query(String.class)
        .list();
  }

  /** A provider that rejects requests with a permanent HTTP 400, like a refused model parameter. */
  private static final class ProviderDouble implements AiCapabilityInvoker {
    final AtomicInteger calls = new AtomicInteger();
    volatile boolean rejectEveryRequest;

    @Override
    @SuppressWarnings("unchecked")
    public <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> resultType) {
      calls.incrementAndGet();
      boolean rejected = rejectEveryRequest || String.valueOf(request.payload()).contains("POISON");
      if (rejected) {
        return new Failed<>(
            new AiError(
                "AI_PROVIDER_UNAVAILABLE",
                "provider",
                false,
                "NVIDIA Build returned HTTP 400",
                request.correlationId(),
                Map.of("providerStatus", 400, "providerFailureScope", "request")));
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
