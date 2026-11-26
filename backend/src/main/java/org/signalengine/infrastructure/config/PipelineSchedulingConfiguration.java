package org.signalengine.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.signalengine.application.dedup.GroupIntoRelevantInformationUseCase;
import org.signalengine.application.ingestion.CollectFromSourceUseCase;
import org.signalengine.application.pipeline.DefaultRunPipelineCycleUseCase;
import org.signalengine.application.pipeline.RunPipelineCycleUseCase;
import org.signalengine.application.signal.ProcessRelevantInformationUseCase;
import org.signalengine.application.signal.ReconcileKnowledgeBaseIndexUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the scheduled pipeline from the existing input ports (composition root — CLAUDE.md Section
 * 9; docs/03-technical-spec.md Section 6.2, 6.5). {@code signal-engine.pipeline.enabled=false}
 * leaves the application without any automatic processing.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "signal-engine.pipeline.enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableScheduling
@EnableConfigurationProperties(PipelineProperties.class)
class PipelineSchedulingConfiguration {

  @Bean
  RunPipelineCycleUseCase runPipelineCycleUseCase(
      CollectFromSourceUseCase collectFromSource,
      GroupIntoRelevantInformationUseCase groupIntoRelevantInformation,
      ProcessRelevantInformationUseCase processRelevantInformation,
      ReconcileKnowledgeBaseIndexUseCase reconcileKnowledgeBaseIndex,
      PipelineProperties properties) {
    return new DefaultRunPipelineCycleUseCase(
        collectFromSource,
        groupIntoRelevantInformation,
        processRelevantInformation,
        reconcileKnowledgeBaseIndex,
        Clock.systemUTC(),
        properties.stageBudget(),
        properties.maxBatchesPerStage());
  }

  @Bean
  PipelineScheduler pipelineScheduler(
      RunPipelineCycleUseCase runPipelineCycleUseCase, MeterRegistry meterRegistry) {
    return new PipelineScheduler(runPipelineCycleUseCase, meterRegistry);
  }
}
