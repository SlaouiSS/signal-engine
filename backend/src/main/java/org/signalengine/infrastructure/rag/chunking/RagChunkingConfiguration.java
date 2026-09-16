package org.signalengine.infrastructure.rag.chunking;

import io.github.semanticchunker.chunker.SemanticChunker;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.rag.chunking.StructureAwareChunker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for RAG chunking (CLAUDE.md Section 9; docs/03-technical-spec.md Section 6.2).
 *
 * <p>Both chunkers are exposed as beans, by their concrete type, with <b>no {@code @Primary}</b>:
 * they are not interchangeable and a later indexing task chooses which to use per content. The
 * deterministic {@link StructureAwareChunker} needs nothing external; the {@link
 * SemanticChunkerAdapter} needs the Task 6A {@link AiCapabilityInvoker} and, at call time, the
 * {@code semantic-chunk-boundary} Python capability backed by a configured provider.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SemanticChunkingProperties.class)
class RagChunkingConfiguration {

  @Bean
  StructureAwareChunker structureAwareChunker(SemanticChunkingProperties properties) {
    return new StructureAwareChunker(properties.structureAwareMaxChars());
  }

  @Bean
  SemanticChunkerAdapter semanticChunkerAdapter(
      AiCapabilityInvoker aiCapabilityInvoker, SemanticChunkingProperties properties) {
    AiCapabilityChunkingModel model =
        new AiCapabilityChunkingModel(aiCapabilityInvoker, properties.maxInputTokens());
    SemanticChunker library =
        SemanticChunker.builder()
            .documentExtractor(new NormalizedTextDocumentExtractor())
            .chunkingModel(model)
            .build();
    String configurationVersion =
        "semantic-chunker-core=1.0.0;capability="
            + AiCapabilityChunkingModel.CAPABILITY
            + "/v"
            + AiCapabilityChunkingModel.CONTRACT_VERSION
            + ";maxInputTokens="
            + properties.maxInputTokens();
    return new SemanticChunkerAdapter(library, properties.defaultMediaType(), configurationVersion);
  }
}
