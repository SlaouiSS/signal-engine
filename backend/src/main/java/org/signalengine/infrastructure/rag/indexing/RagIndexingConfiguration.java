package org.signalengine.infrastructure.rag.indexing;

import java.time.Clock;
import org.signalengine.application.ai.AiCapabilityFailureException;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.signal.DefaultReconcileKnowledgeBaseIndexUseCase;
import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.application.signal.ReconcileKnowledgeBaseIndexUseCase;
import org.signalengine.infrastructure.rag.chunking.SemanticChunkerAdapter;
import org.signalengine.rag.chunking.Chunker;
import org.signalengine.rag.chunking.StructureAwareChunker;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.indexing.IndexedPassageStore;
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
        // A suspended capability fails for everyone: that is not this content's fault, so it must
        // not use up the content's bounded attempts (docs/adr/0018-pipeline-reliability.md).
        .failureCountsAsAttempt(
            failure ->
                !AiCapabilityFailureException.findIn(failure)
                    .map(AiError::capabilitySuspended)
                    .orElse(false))
        .build();
  }

  @Bean
  KnowledgeBaseIndexBacklog knowledgeBaseIndexBacklog(
      JdbcClient jdbcClient, @Qualifier("indexingChunker") Chunker chunker) {
    return new KnowledgeBaseIndexBacklog(jdbcClient, chunker);
  }

  @Bean
  KnowledgeBaseIndexer knowledgeBaseIndexer(
      IndexingPipeline indexingPipeline, KnowledgeBaseIndexBacklog knowledgeBaseIndexBacklog) {
    return new SignalEngineKnowledgeBaseIndexer(indexingPipeline, knowledgeBaseIndexBacklog);
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
        properties.maxFailedAttempts(),
        properties.reconciliationBatchLimit());
  }
}
