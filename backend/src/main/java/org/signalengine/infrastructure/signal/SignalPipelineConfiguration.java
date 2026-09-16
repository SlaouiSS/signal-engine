package org.signalengine.infrastructure.signal;

import java.time.Clock;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.application.persistence.InterestRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.persistence.SummaryRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.application.signal.DefaultImportanceAssessor;
import org.signalengine.application.signal.DefaultProcessRelevantInformationUseCase;
import org.signalengine.application.signal.DefaultRelevanceAssessor;
import org.signalengine.application.signal.DefaultSummaryGenerator;
import org.signalengine.application.signal.ImportanceAssessor;
import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.application.signal.ProcessRelevantInformationUseCase;
import org.signalengine.application.signal.RelevanceAssessor;
import org.signalengine.application.signal.SummaryGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for the relevance/importance/Signal/Summary pipeline (docs/03-technical-spec.md
 * Section 6.2; CLAUDE.md Section 9). Plain application classes wired by constructor injection; each
 * AI step goes through its own port over the Task 6A {@link AiCapabilityInvoker}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SignalPipelineProperties.class)
class SignalPipelineConfiguration {

  @Bean
  RelevanceAssessor relevanceAssessor(AiCapabilityInvoker aiCapabilityInvoker) {
    return new DefaultRelevanceAssessor(aiCapabilityInvoker);
  }

  @Bean
  ImportanceAssessor importanceAssessor(AiCapabilityInvoker aiCapabilityInvoker) {
    return new DefaultImportanceAssessor(aiCapabilityInvoker);
  }

  @Bean
  SummaryGenerator summaryGenerator(AiCapabilityInvoker aiCapabilityInvoker) {
    return new DefaultSummaryGenerator(aiCapabilityInvoker);
  }

  @Bean
  ProcessRelevantInformationUseCase processRelevantInformationUseCase(
      RelevantInformationRepository relevantInformationRepository,
      RawInformationItemRepository rawInformationItemRepository,
      SignalRepository signalRepository,
      SummaryRepository summaryRepository,
      SourceRepository sourceRepository,
      AreaOfInterestRepository areaOfInterestRepository,
      InterestRepository interestRepository,
      RelevanceAssessor relevanceAssessor,
      ImportanceAssessor importanceAssessor,
      SummaryGenerator summaryGenerator,
      KnowledgeBaseIndexer knowledgeBaseIndexer,
      ActivityRecordRepository activityRecordRepository,
      UnitOfWork unitOfWork,
      SignalPipelineProperties properties) {
    return new DefaultProcessRelevantInformationUseCase(
        relevantInformationRepository,
        rawInformationItemRepository,
        signalRepository,
        summaryRepository,
        sourceRepository,
        areaOfInterestRepository,
        interestRepository,
        relevanceAssessor,
        importanceAssessor,
        summaryGenerator,
        knowledgeBaseIndexer,
        activityRecordRepository,
        unitOfWork,
        Clock.systemUTC(),
        properties.batchLimit());
  }
}
