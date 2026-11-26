package org.signalengine.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.signalengine.application.dedup.GroupIntoRelevantInformationUseCase;
import org.signalengine.application.ingestion.CollectFromSourceUseCase;
import org.signalengine.application.pipeline.RunPipelineCycleUseCase;
import org.signalengine.application.pipeline.StageReport;
import org.signalengine.application.signal.ProcessRelevantInformationUseCase;
import org.signalengine.application.signal.ReconcileKnowledgeBaseIndexUseCase;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The scheduled trigger with Spring's real scheduler: it repeats and never overlaps itself. */
class PipelineSchedulerTest {

  @Test
  void cyclesRepeatWithoutARestartAndNeverOverlap() {
    AtomicInteger running = new AtomicInteger();
    AtomicInteger mostAtOnce = new AtomicInteger();
    CountDownLatch threeCycles = new CountDownLatch(3);
    RunPipelineCycleUseCase slowCycle =
        () -> {
          mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
          pause(Duration.ofMillis(80));
          running.decrementAndGet();
          threeCycles.countDown();
          return List.of();
        };

    new ApplicationContextRunner()
        .withPropertyValues("signal-engine.pipeline.fixed-delay=10ms")
        .withBean(RunPipelineCycleUseCase.class, () -> slowCycle)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withUserConfiguration(SchedulingOnly.class)
        .run(
            context -> {
              assertThat(threeCycles.await(10, TimeUnit.SECONDS)).isTrue();
              assertThat(mostAtOnce).hasValue(1);
            });
  }

  @Test
  void aFailedCycleIsContainedAndEachStagePublishesItsDurationAndOutcomes() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PipelineScheduler failing =
        new PipelineScheduler(
            () -> {
              throw new IllegalStateException("database away");
            },
            meters);
    PipelineScheduler reporting =
        new PipelineScheduler(
            () ->
                List.of(
                    new StageReport(
                        "grouping", Duration.ofSeconds(2), Map.of("CREATED_NEW", 3), false)),
            meters);

    failing.runScheduledCycle();
    reporting.runScheduledCycle();

    assertThat(
            meters
                .counter(
                    PipelineScheduler.STAGE_OUTCOME_METER,
                    "stage",
                    "grouping",
                    "outcome",
                    "CREATED_NEW")
                .count())
        .isEqualTo(3);
    assertThat(meters.timer(PipelineScheduler.STAGE_DURATION_METER, "stage", "grouping").count())
        .isEqualTo(1);
  }

  @Test
  void aDisabledPipelineIsNotWired() {
    new ApplicationContextRunner()
        .withPropertyValues("signal-engine.pipeline.enabled=false")
        .withUserConfiguration(PipelineSchedulingConfiguration.class)
        .run(context -> assertThat(context).doesNotHaveBean(PipelineScheduler.class));
  }

  @Test
  void anEnabledPipelineIsWiredFromTheFourInputPorts() {
    new ApplicationContextRunner()
        .withPropertyValues("signal-engine.pipeline.initial-delay=1h")
        .withBean(CollectFromSourceUseCase.class, () -> mock(CollectFromSourceUseCase.class))
        .withBean(
            GroupIntoRelevantInformationUseCase.class,
            () -> mock(GroupIntoRelevantInformationUseCase.class))
        .withBean(
            ProcessRelevantInformationUseCase.class,
            () -> mock(ProcessRelevantInformationUseCase.class))
        .withBean(
            ReconcileKnowledgeBaseIndexUseCase.class,
            () -> mock(ReconcileKnowledgeBaseIndexUseCase.class))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withUserConfiguration(PipelineSchedulingConfiguration.class)
        .run(context -> assertThat(context).hasSingleBean(PipelineScheduler.class));
  }

  private static void pause(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @EnableScheduling
  static class SchedulingOnly {
    @org.springframework.context.annotation.Bean
    PipelineScheduler pipelineScheduler(RunPipelineCycleUseCase cycle, MeterRegistry meters) {
      return new PipelineScheduler(cycle, meters);
    }
  }
}
