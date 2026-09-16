package org.signalengine.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.signalengine.application.dedup.GroupIntoRelevantInformationUseCase;
import org.signalengine.application.ingestion.CollectFromSourceUseCase;
import org.signalengine.application.signal.ProcessRelevantInformationUseCase;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * {@link StartupIngestionRunner} — the startup ingestion trigger (docs/08-ingestion.md Section 16).
 * A synchronous ({@code Runnable::run}) executor is used for the ordering/failure-isolation tests
 * so assertions run deterministically on the test thread; a real background executor is used once,
 * to prove {@link StartupIngestionRunner#run} itself never waits for the pipeline.
 */
class StartupIngestionRunnerTest {

  private static final Executor SAME_THREAD_EXECUTOR = Runnable::run;
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

  private final CollectFromSourceUseCase collectFromSource = mock(CollectFromSourceUseCase.class);
  private final GroupIntoRelevantInformationUseCase groupIntoRelevantInformation =
      mock(GroupIntoRelevantInformationUseCase.class);
  private final ProcessRelevantInformationUseCase processRelevantInformation =
      mock(ProcessRelevantInformationUseCase.class);

  @Test
  void runsCollectThenGroupThenProcessInOrder() {
    when(collectFromSource.collectFromEnabledSources()).thenReturn(List.of());
    when(groupIntoRelevantInformation.groupNormalizedRawInformationItems()).thenReturn(List.of());
    when(processRelevantInformation.processPending()).thenReturn(List.of());
    StartupIngestionRunner runner = runnerWith(SAME_THREAD_EXECUTOR);

    runner.run(new DefaultApplicationArguments());

    InOrder order =
        inOrder(collectFromSource, groupIntoRelevantInformation, processRelevantInformation);
    order.verify(collectFromSource).collectFromEnabledSources();
    order.verify(groupIntoRelevantInformation).groupNormalizedRawInformationItems();
    order.verify(processRelevantInformation).processPending();
    verifyNoMoreInteractions(
        collectFromSource, groupIntoRelevantInformation, processRelevantInformation);
  }

  @Test
  void invokesEachStageExactlyOncePerRunCall() {
    // ApplicationRunner#run is called exactly once by Spring Boot per application start; this
    // confirms one call to run(...) drives exactly one execution of each stage, with no internal
    // retry or resubmission of its own.
    when(collectFromSource.collectFromEnabledSources()).thenReturn(List.of());
    when(groupIntoRelevantInformation.groupNormalizedRawInformationItems()).thenReturn(List.of());
    when(processRelevantInformation.processPending()).thenReturn(List.of());
    StartupIngestionRunner runner = runnerWith(SAME_THREAD_EXECUTOR);

    runner.run(new DefaultApplicationArguments());

    verify(collectFromSource, times(1)).collectFromEnabledSources();
    verify(groupIntoRelevantInformation, times(1)).groupNormalizedRawInformationItems();
    verify(processRelevantInformation, times(1)).processPending();
  }

  @Test
  void aFailureInAnEarlyStageDoesNotPropagateAndSkipsLaterStages() {
    when(collectFromSource.collectFromEnabledSources())
        .thenThrow(new RuntimeException("source repository unavailable"));
    StartupIngestionRunner runner = runnerWith(SAME_THREAD_EXECUTOR);

    // Must not throw — a failure here must never be treated as an application startup failure.
    runner.run(new DefaultApplicationArguments());

    verify(groupIntoRelevantInformation, never()).groupNormalizedRawInformationItems();
    verify(processRelevantInformation, never()).processPending();
  }

  @Test
  void aFailureInTheLastStageDoesNotPropagate() {
    when(collectFromSource.collectFromEnabledSources()).thenReturn(List.of());
    when(groupIntoRelevantInformation.groupNormalizedRawInformationItems()).thenReturn(List.of());
    when(processRelevantInformation.processPending())
        .thenThrow(new RuntimeException("unexpected failure"));
    StartupIngestionRunner runner = runnerWith(SAME_THREAD_EXECUTOR);

    runner.run(new DefaultApplicationArguments());
  }

  @Test
  void runReturnsImmediatelyWithoutWaitingForTheBackgroundPipeline() throws InterruptedException {
    CountDownLatch releasePipeline = new CountDownLatch(1);
    CountDownLatch pipelineStarted = new CountDownLatch(1);
    when(collectFromSource.collectFromEnabledSources())
        .thenAnswer(
            invocation -> {
              pipelineStarted.countDown();
              assertThat(releasePipeline.await(5, TimeUnit.SECONDS)).isTrue();
              return List.of();
            });
    when(groupIntoRelevantInformation.groupNormalizedRawInformationItems()).thenReturn(List.of());
    when(processRelevantInformation.processPending()).thenReturn(List.of());
    Executor realBackgroundExecutor = task -> new Thread(task, "test-startup-ingestion").start();
    StartupIngestionRunner runner = runnerWith(realBackgroundExecutor);

    long before = System.nanoTime();
    runner.run(new DefaultApplicationArguments());
    long elapsedMillis = (System.nanoTime() - before) / 1_000_000;

    // run() returned long before the (deliberately blocked) pipeline could have completed —
    // application startup is never held up by it.
    assertThat(elapsedMillis).isLessThan(1000);
    assertThat(pipelineStarted.await(5, TimeUnit.SECONDS)).isTrue();
    releasePipeline.countDown();
  }

  private StartupIngestionRunner runnerWith(Executor executor) {
    return new StartupIngestionRunner(
        collectFromSource,
        groupIntoRelevantInformation,
        processRelevantInformation,
        executor,
        FIXED_CLOCK);
  }
}
