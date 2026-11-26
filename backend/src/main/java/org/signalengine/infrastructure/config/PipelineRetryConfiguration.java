package org.signalengine.infrastructure.config;

import java.time.Clock;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.application.pipeline.DefaultRequeueExhaustedWorkUseCase;
import org.signalengine.application.pipeline.ItemRetryScheduler;
import org.signalengine.application.pipeline.RequeueExhaustedWorkUseCase;
import org.signalengine.application.pipeline.RetryPolicy;
import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.infrastructure.ai.AiCapabilitySuspensionProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for pipeline retries and the requeueing of exhausted work. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PipelineRetryProperties.class)
class PipelineRetryConfiguration {

  /**
   * An outage uses at most one attempt less than the circuit breaker's threshold, so the item
   * budget must be at least the threshold for one outage never to exhaust an untried item
   * (docs/adr/0019-pipeline-automation.md).
   */
  @Bean
  RetryPolicy itemRetryPolicy(
      PipelineRetryProperties properties, AiCapabilitySuspensionProperties suspension) {
    if (properties.maxAttempts() < suspension.transientFailureThreshold()) {
      throw new IllegalStateException(
          "signal-engine.pipeline.retry.max-attempts must be at least"
              + " signal-engine.ai.suspension.transient-failure-threshold");
    }
    return properties.toRetryPolicy(properties.maxAttempts());
  }

  @Bean
  ItemRetryScheduler itemRetryScheduler(
      RawInformationItemRepository rawInformationItemRepository,
      UnitOfWork unitOfWork,
      @Qualifier("itemRetryPolicy") RetryPolicy retryPolicy) {
    return new ItemRetryScheduler(
        rawInformationItemRepository, unitOfWork, retryPolicy, Clock.systemUTC());
  }

  @Bean
  RequeueExhaustedWorkUseCase requeueExhaustedWorkUseCase(
      RawInformationItemRepository rawInformationItemRepository,
      KnowledgeBaseIndexer knowledgeBaseIndexer,
      ActivityRecordRepository activityRecordRepository,
      UnitOfWork unitOfWork) {
    return new DefaultRequeueExhaustedWorkUseCase(
        rawInformationItemRepository,
        knowledgeBaseIndexer,
        activityRecordRepository,
        unitOfWork,
        Clock.systemUTC());
  }
}
