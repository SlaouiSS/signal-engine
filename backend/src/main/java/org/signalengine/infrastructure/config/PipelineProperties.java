package org.signalengine.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The bounds of one pipeline cycle (docs/adr/0019-pipeline-automation.md). The cadence itself
 * ({@code signal-engine.pipeline.fixed-delay}, default 1h) is read by {@link PipelineScheduler}.
 * Provisional defaults (Q9, T9).
 *
 * @param stageBudget how long a stage may keep starting new units of work
 * @param maxBatchesPerStage how many batches a stage takes from its backlog in one cycle
 */
@ConfigurationProperties("signal-engine.pipeline")
public record PipelineProperties(
    @DefaultValue("15m") Duration stageBudget, @DefaultValue("5") int maxBatchesPerStage) {}
