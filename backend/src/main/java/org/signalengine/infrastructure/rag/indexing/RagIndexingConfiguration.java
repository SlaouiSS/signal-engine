package org.signalengine.infrastructure.rag.indexing;

import java.time.Clock;
import java.util.Optional;
import org.signalengine.application.ai.AiCapabilityFailureException;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.pipeline.RetryPolicy;
import org.signalengine.application.signal.DefaultReconcileKnowledgeBaseIndexUseCase;
import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.application.signal.ReconcileKnowledgeBaseIndexUseCase;
import org.signalengine.infrastructure.ai.AiCallDeadline;
import org.signalengine.infrastructure.config.PipelineRetryProperties;
import org.signalengine.infrastructure.rag.chunking.SemanticChunkerAdapter;
import org.signalengine.rag.chunking.Chunker;
import org.signalengine.rag.chunking.StructureAwareChunker;
import org.signalengine.rag.embedding.EmbeddingException;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.indexing.AbandonedIndexing;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.indexing.IndexingException;
import org.signalengine.rag.indexing.IndexingPipeline;
import org.signalengine.rag.indexing.StagedIndexingPipeline;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Composition root for RAG indexing (CLAUDE.md Section 9; docs/03-technical-spec.md Section 6.2).
 * Wires the generic {@link StagedIndexingPipeline} from the chosen chunker, the Task 8.3A {@link
 * EmbeddingModel}, and the pgvector {@link IndexedPassageStore}; and {@link KnowledgeBaseIndexer},
 * the thin adapter {@code DefaultProcessRelevantInformationUseCase} calls once an item is confirmed
 * relevant (the bounded Phase 8 index-population wiring).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagIndexingProperties.class)
class RagIndexingConfiguration {

  @Bean
  Chunker indexingChunker(
      StructureAwareChunker structureAwareChunker,
      SemanticChunkerAdapter semanticChunkerAdapter,
      RagIndexingProperties properties) {
    return "semantic".equalsIgnoreCase(properties.chunker())
        ? semanticChunkerAdapter
        : structureAwareChunker;
  }

  @Bean
  IndexingPipeline stagedIndexingPipeline(
      @Qualifier("indexingChunker") Chunker chunker,
      EmbeddingModel embeddingModel,
      IndexedPassageStore indexedPassageStore) {
    return StagedIndexingPipeline.builder()
        .chunker(chunker)
        .embeddingModel(embeddingModel)
        .store(indexedPassageStore)
        .failureClassifier(RagIndexingConfiguration::classify)
        .build();
  }

  /**
   * What a failed indexing costs the content (docs/adr/0019-pipeline-automation.md). A suspended
   * capability fails for everyone and a shutdown fails nothing: neither is the content's fault.
   */
  static AbandonedIndexing classify(RuntimeException failure) {
    String reason = String.valueOf(failure.getMessage());
    Optional<AiError> aiError = AiCapabilityFailureException.findIn(failure);
    if (Thread.currentThread().isInterrupted()
        || aiError.map(AiError::capabilitySuspended).orElse(false)) {
      return new AbandonedIndexing(AbandonedIndexing.Cause.NOT_THE_CONTENT, true, reason);
    }
    if (aiError.map(AiError::rejectsThisInput).orElse(false)) {
      return new AbandonedIndexing(AbandonedIndexing.Cause.CONTENT_REJECTED, true, reason);
    }
    if (aiError.map(AiError::timeBudgetExceeded).orElse(false)) {
      return new AbandonedIndexing(AbandonedIndexing.Cause.TIME_BUDGET_EXCEEDED, true, reason);
    }
    if (aiError.isPresent()) {
      return new AbandonedIndexing(
          AbandonedIndexing.Cause.FAILED_ATTEMPT, aiError.get().retryable(), reason);
    }
    if (failure instanceof IndexingException || failure instanceof EmbeddingException) {
      // A contract violation of the chunking or embedding result: attempting again changes nothing.
      return new AbandonedIndexing(AbandonedIndexing.Cause.FAILED_ATTEMPT, false, reason);
    }
    return new AbandonedIndexing(AbandonedIndexing.Cause.UNEXPECTED_ERROR, true, reason);
  }

  /**
   * Indexing shares the pipeline's backoff, with its own number of attempts per content version and
   * chunker configuration.
   */
  @Bean
  RetryPolicy indexingRetryPolicy(
      RagIndexingProperties properties, PipelineRetryProperties retryProperties) {
    return retryProperties.toRetryPolicy(properties.maxFailedAttempts());
  }

  @Bean
  KnowledgeBaseIndexBacklog knowledgeBaseIndexBacklog(
      JdbcClient jdbcClient, @Qualifier("indexingChunker") Chunker chunker) {
    return new KnowledgeBaseIndexBacklog(jdbcClient, chunker);
  }

  @Bean
  KnowledgeBaseIndexer knowledgeBaseIndexer(
      IndexingPipeline indexingPipeline,
      KnowledgeBaseIndexBacklog knowledgeBaseIndexBacklog,
      AiCallDeadline aiCallDeadline,
      RagIndexingProperties properties) {
    return new SignalEngineKnowledgeBaseIndexer(
        indexingPipeline, knowledgeBaseIndexBacklog, aiCallDeadline, properties.timeBudget());
  }

  @Bean
  ReconcileKnowledgeBaseIndexUseCase reconcileKnowledgeBaseIndexUseCase(
      RawInformationItemRepository rawInformationItemRepository,
      SourceRepository sourceRepository,
      KnowledgeBaseIndexer knowledgeBaseIndexer,
      ActivityRecordRepository activityRecordRepository,
      RagIndexingProperties properties) {
    return new DefaultReconcileKnowledgeBaseIndexUseCase(
        knowledgeBaseIndexer,
        rawInformationItemRepository,
        sourceRepository,
        activityRecordRepository,
        Clock.systemUTC(),
        properties.reconciliationBatchLimit());
  }
}
