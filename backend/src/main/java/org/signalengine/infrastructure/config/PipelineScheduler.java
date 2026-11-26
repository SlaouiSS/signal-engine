package org.signalengine.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.signalengine.application.pipeline.RunPipelineCycleUseCase;
import org.signalengine.application.pipeline.StageReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs a pipeline cycle when the backend starts, and again a fixed delay after each cycle ends
 * (docs/adr/0019-pipeline-automation.md; docs/03-technical-spec.md Section 6.5). The delay counts
 * from the end of a cycle, so within one backend instance a cycle never overlaps the previous one.
 * Nothing here coordinates several instances, which are not supported.
 *
 * <p>Each cycle is logged under its own id, and publishes the duration and the outcomes of every
 * stage.
 */
class PipelineScheduler {

  static final String STAGE_DURATION_METER = "signal_engine.pipeline.stage.duration";
  static final String STAGE_OUTCOME_METER = "signal_engine.pipeline.stage.outcomes";
  static final String STAGE_FAILURE_METER = "signal_engine.pipeline.stage.failures";

  private static final Logger log = LoggerFactory.getLogger(PipelineScheduler.class);

  private final RunPipelineCycleUseCase runPipelineCycle;
  private final MeterRegistry meterRegistry;

  PipelineScheduler(RunPipelineCycleUseCase runPipelineCycle, MeterRegistry meterRegistry) {
    this.runPipelineCycle = runPipelineCycle;
    this.meterRegistry = meterRegistry;
  }

  @Scheduled(
      initialDelayString = "${signal-engine.pipeline.initial-delay:0s}",
      fixedDelayString = "${signal-engine.pipeline.fixed-delay:1h}")
  void runScheduledCycle() {
    String cycleId = UUID.randomUUID().toString();
    log.info("Pipeline cycle {}: starting", cycleId);
    try {
      List<StageReport> stages = runPipelineCycle.runCycle();
      stages.forEach(stage -> publish(cycleId, stage));
      log.info("Pipeline cycle {}: completed", cycleId);
    } catch (RuntimeException failure) {
      log.error("Pipeline cycle {}: failed; the next cycle runs as scheduled", cycleId, failure);
    }
  }

  private void publish(String cycleId, StageReport stage) {
    log.info(
        "Pipeline cycle {}: stage {} took {}, outcomes {}{}",
        cycleId,
        stage.stage(),
        stage.duration(),
        stage.outcomes(),
        stage.failed() ? ", STAGE FAILED" : "");
    meterRegistry.timer(STAGE_DURATION_METER, "stage", stage.stage()).record(stage.duration());
    stage
        .outcomes()
        .forEach(
            (outcome, count) ->
                meterRegistry
                    .counter(STAGE_OUTCOME_METER, "stage", stage.stage(), "outcome", outcome)
                    .increment(count));
    if (stage.failed()) {
      meterRegistry.counter(STAGE_FAILURE_METER, "stage", stage.stage()).increment();
    }
  }
}
