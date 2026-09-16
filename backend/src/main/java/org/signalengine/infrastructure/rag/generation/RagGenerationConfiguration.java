package org.signalengine.infrastructure.rag.generation;

import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.rag.generation.AnswerValidator;
import org.signalengine.rag.generation.Generator;
import org.signalengine.rag.generation.GroundingAnswerValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for RAG grounded generation (CLAUDE.md Section 9; docs/03-technical-spec.md
 * Section 6.2; docs/adr/0015-rag-grounded-generator.md).
 *
 * <p>Wires the RAG core {@link Generator} to the {@code answer} Python capability over the existing
 * Task 6A transport, and the structural {@link GroundingAnswerValidator}. The query-time {@link
 * org.signalengine.rag.pipeline.RagPipeline} bean (in {@code RagContextConfiguration}) picks both
 * up as optional stages, consumed by {@code AskQuestionUseCase} behind the {@code
 * /api/v1/questions} endpoint.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagGenerationProperties.class)
class RagGenerationConfiguration {

  @Bean
  Generator aiCapabilityAnswerGenerator(
      AiCapabilityInvoker aiCapabilityInvoker, RagGenerationProperties properties) {
    return new AiCapabilityAnswerGenerator(aiCapabilityInvoker, properties.contractVersion());
  }

  @Bean
  AnswerValidator groundingAnswerValidator() {
    return new GroundingAnswerValidator();
  }
}
