package org.signalengine.infrastructure.rag.indexing;

import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.infrastructure.rag.chunking.SemanticChunkerAdapter;
import org.signalengine.rag.chunking.Chunker;
import org.signalengine.rag.chunking.StructureAwareChunker;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.indexing.IndexingPipeline;
import org.signalengine.rag.indexing.StagedIndexingPipeline;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
  IndexingPipeline stagedIndexingPipeline(
      StructureAwareChunker structureAwareChunker,
      SemanticChunkerAdapter semanticChunkerAdapter,
      EmbeddingModel embeddingModel,
      IndexedPassageStore indexedPassageStore,
      RagIndexingProperties properties) {
    Chunker chunker =
        "semantic".equalsIgnoreCase(properties.chunker())
            ? semanticChunkerAdapter
            : structureAwareChunker;
    return StagedIndexingPipeline.builder()
        .chunker(chunker)
        .embeddingModel(embeddingModel)
        .store(indexedPassageStore)
        .build();
  }

  @Bean
  KnowledgeBaseIndexer knowledgeBaseIndexer(IndexingPipeline indexingPipeline) {
    return new SignalEngineKnowledgeBaseIndexer(indexingPipeline);
  }
}
