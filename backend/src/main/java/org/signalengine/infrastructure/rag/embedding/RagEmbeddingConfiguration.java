package org.signalengine.infrastructure.rag.embedding;

import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for the RAG embedding path (CLAUDE.md Section 9; docs/03-technical-spec.md
 * Section 6.2). Wires the RAG core {@link EmbeddingModel} port to the {@code embed} Python
 * capability over the existing Task 6A transport. Nothing consumes the bean yet.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmbeddingProperties.class)
class RagEmbeddingConfiguration {

  @Bean
  EmbeddingModel aiCapabilityEmbeddingModel(
      AiCapabilityInvoker aiCapabilityInvoker, EmbeddingProperties properties) {
    return new AiCapabilityEmbeddingModel(
        aiCapabilityInvoker,
        properties.capability(),
        properties.contractVersion(),
        properties.expectedDimension());
  }
}
