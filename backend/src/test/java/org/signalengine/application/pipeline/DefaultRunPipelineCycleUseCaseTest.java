package org.signalengine.application.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.signalengine.application.dedup.GroupIntoRelevantInformationUseCase;
import org.signalengine.application.dedup.GroupingReport;
import org.signalengine.application.ingestion.CollectFromSourceUseCase;
import org.signalengine.application.signal.ProcessRelevantInformationUseCase;
import org.signalengine.application.signal.ReconcileKnowledgeBaseIndexUseCase;

/** The bounds of one cycle: batches, time, progress, and isolation between stages. */
class DefaultRunPipelineCycleUseCaseTest {

  private final CollectFromSourceUseCase collect = mock(CollectFromSourceUseCase.class);
  private final GroupIntoRelevantInformationUseCase group =
      mock(GroupIntoRelevantInformationUseCase.class);
  private final ProcessRelevantInformationUseCase process =
      mock(ProcessRelevantInformationUseCase.class);
  private final ReconcileKnowledgeBaseIndexUseCase reconcile =
      mock(ReconcileKnowledgeBaseIndexUseCase.class);
  private final MutableClock clock = new MutableClock();

  private DefaultRunPipelineCycleUseCase cycle() {
    return new DefaultRunPipelineCycleUseCase(
        collect, group, process, reconcile, clock, Duration.ofMinutes(15), 5);
  }

  private static GroupingReport created() {
    return new GroupingReport(
        UUID.randomUUID(), GroupingReport.Outcome.CREATED_NEW, UUID.randomUUID(), null);
  }

  @Test
  void aStageTakesAtMostTheMaximumNumberOfBatches() {
    when(group.groupNormalizedRawInformationItems(any())).thenReturn(List.of(created()));

    List<StageReport> reports = cycle().runCycle();

    verify(group, times(5)).groupNormalizedRawInformationItems(any());
    assertThat(reports.get(1).outcomes()).containsEntry("CREATED_NEW", 5);
  }

  @Test
  void aBatchThatMovesNothingForwardEndsTheStage() {
    when(group.groupNormalizedRawInformationItems(any()))
        .thenReturn(
            List.of(
                new GroupingReport(
                    UUID.randomUUID(), GroupingReport.Outcome.SUSPENDED, null, "suspended")));

    cycle().runCycle();

    verify(group, times(1)).groupNormalizedRawInformationItems(any());
  }

  @Test
  void noBatchStartsOnceTheStageBudgetIsSpentAndTheNextStageHasItsOwn() {
    when(group.groupNormalizedRawInformationItems(any()))
        .thenAnswer(
            invocation -> {
              clock.advance(Duration.ofMinutes(16));
              return List.of(created());
            });
    when(process.processPending(any()))
        .thenAnswer(
            invocation -> {
              assertThat(invocation.<StageBudget>getArgument(0).exhausted()).isFalse();
              return List.of();
            });

    cycle().runCycle();

    verify(group, times(1)).groupNormalizedRawInformationItems(any());
    verify(process).processPending(any());
  }

  @Test
  void aFailingStageDoesNotStopTheFollowingOnes() {
    when(collect.collectFromEnabledSources(any())).thenThrow(new IllegalStateException("boom"));

    List<StageReport> reports = cycle().runCycle();

    assertThat(reports).extracting(StageReport::failed).containsExactly(true, false, false, false);
    verify(group).groupNormalizedRawInformationItems(any());
    verify(process).processPending(any());
    verify(reconcile).reconcilePending(any());
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-08-01T00:00:00Z"));

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
