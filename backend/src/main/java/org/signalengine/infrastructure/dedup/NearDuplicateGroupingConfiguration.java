package org.signalengine.infrastructure.dedup;

import java.time.Clock;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.dedup.DefaultGroupIntoRelevantInformationUseCase;
import org.signalengine.application.dedup.DefaultNearDuplicateAssessor;
import org.signalengine.application.dedup.GroupIntoRelevantInformationUseCase;
import org.signalengine.application.dedup.NearDuplicateAssessor;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for semantic near-duplicate grouping (docs/03-technical-spec.md Section 6.2;
 * CLAUDE.md Section 9). Plain application classes, wired here by constructor injection; the AI call
 * goes through the Task 6A {@link AiCapabilityInvoker} port.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NearDuplicateGroupingProperties.class)
class NearDuplicateGroupingConfiguration {

  @Bean
  NearDuplicateAssessor nearDuplicateAssessor(AiCapabilityInvoker aiCapabilityInvoker) {
    return new DefaultNearDuplicateAssessor(aiCapabilityInvoker);
  }

  @Bean
  GroupIntoRelevantInformationUseCase groupIntoRelevantInformationUseCase(
      RawInformationItemRepository rawInformationItemRepository,
      RelevantInformationRepository relevantInformationRepository,
      NearDuplicateAssessor nearDuplicateAssessor,
      ActivityRecordRepository activityRecordRepository,
      UnitOfWork unitOfWork,
      NearDuplicateGroupingProperties properties) {
    return new DefaultGroupIntoRelevantInformationUseCase(
        rawInformationItemRepository,
        relevantInformationRepository,
        nearDuplicateAssessor,
        activityRecordRepository,
        unitOfWork,
        Clock.systemUTC(),
        properties.candidatePoolSize(),
        properties.batchLimit());
  }
}
