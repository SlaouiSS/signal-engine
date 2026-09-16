package org.signalengine.infrastructure.config;

import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.application.persistence.FeedbackRepository;
import org.signalengine.application.persistence.InterestRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.application.usecase.AskQuestionUseCase;
import org.signalengine.application.usecase.DefaultAskQuestionUseCase;
import org.signalengine.application.usecase.DefaultManageInterestsUseCase;
import org.signalengine.application.usecase.DefaultManageSourcesUseCase;
import org.signalengine.application.usecase.DefaultReviewActivityUseCase;
import org.signalengine.application.usecase.DefaultReviewAreasOfInterestUseCase;
import org.signalengine.application.usecase.DefaultReviewRawInformationUseCase;
import org.signalengine.application.usecase.DefaultReviewRelevantInformationUseCase;
import org.signalengine.application.usecase.DefaultReviewSignalsUseCase;
import org.signalengine.application.usecase.DefaultSemanticSearchUseCase;
import org.signalengine.application.usecase.DefaultSubmitFeedbackUseCase;
import org.signalengine.application.usecase.ManageInterestsUseCase;
import org.signalengine.application.usecase.ManageSourcesUseCase;
import org.signalengine.application.usecase.ReviewActivityUseCase;
import org.signalengine.application.usecase.ReviewAreasOfInterestUseCase;
import org.signalengine.application.usecase.ReviewRawInformationUseCase;
import org.signalengine.application.usecase.ReviewRelevantInformationUseCase;
import org.signalengine.application.usecase.ReviewSignalsUseCase;
import org.signalengine.application.usecase.SemanticSearchUseCase;
import org.signalengine.application.usecase.SubmitFeedbackUseCase;
import org.signalengine.rag.pipeline.RagPipeline;
import org.signalengine.rag.retrieval.Retriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for the application use cases (docs/03-technical-spec.md Section 6.2; CLAUDE.md
 * Section 9).
 *
 * <p>The use-case implementations are plain classes with no Spring annotations; Spring is a wiring
 * concern that stays in infrastructure. Each {@code @Bean} method constructs one implementation and
 * receives its output-port beans (repository adapters, {@link UnitOfWork}) by constructor
 * injection.
 */
@Configuration(proxyBeanMethods = false)
class UseCaseConfiguration {

  @Bean
  ManageSourcesUseCase manageSourcesUseCase(SourceRepository sourceRepository) {
    return new DefaultManageSourcesUseCase(sourceRepository);
  }

  @Bean
  ReviewAreasOfInterestUseCase reviewAreasOfInterestUseCase(
      AreaOfInterestRepository areaOfInterestRepository) {
    return new DefaultReviewAreasOfInterestUseCase(areaOfInterestRepository);
  }

  @Bean
  ManageInterestsUseCase manageInterestsUseCase(
      InterestRepository interestRepository, AreaOfInterestRepository areaOfInterestRepository) {
    return new DefaultManageInterestsUseCase(interestRepository, areaOfInterestRepository);
  }

  @Bean
  ReviewRawInformationUseCase reviewRawInformationUseCase(
      RawInformationItemRepository rawInformationItemRepository) {
    return new DefaultReviewRawInformationUseCase(rawInformationItemRepository);
  }

  @Bean
  ReviewRelevantInformationUseCase reviewRelevantInformationUseCase(
      RelevantInformationRepository relevantInformationRepository) {
    return new DefaultReviewRelevantInformationUseCase(relevantInformationRepository);
  }

  @Bean
  ReviewSignalsUseCase reviewSignalsUseCase(SignalRepository signalRepository) {
    return new DefaultReviewSignalsUseCase(signalRepository);
  }

  @Bean
  SubmitFeedbackUseCase submitFeedbackUseCase(
      FeedbackRepository feedbackRepository,
      SignalRepository signalRepository,
      UnitOfWork unitOfWork) {
    return new DefaultSubmitFeedbackUseCase(feedbackRepository, signalRepository, unitOfWork);
  }

  @Bean
  ReviewActivityUseCase reviewActivityUseCase(ActivityRecordRepository activityRecordRepository) {
    return new DefaultReviewActivityUseCase(activityRecordRepository);
  }

  @Bean
  SemanticSearchUseCase semanticSearchUseCase(Retriever retriever) {
    return new DefaultSemanticSearchUseCase(retriever);
  }

  @Bean
  AskQuestionUseCase askQuestionUseCase(RagPipeline ragPipeline) {
    return new DefaultAskQuestionUseCase(ragPipeline);
  }
}
