package org.signalengine.infrastructure.rag.context;

import org.signalengine.rag.context.BudgetedContextAssembler;
import org.signalengine.rag.context.ContextAssembler;
import org.signalengine.rag.generation.AnswerValidator;
import org.signalengine.rag.generation.Generator;
import org.signalengine.rag.pipeline.RagPipeline;
import org.signalengine.rag.pipeline.StagedRagPipeline;
import org.signalengine.rag.retrieval.Retriever;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for RAG context assembly and the query-time pipeline (CLAUDE.md Section 9;
 * docs/03-technical-spec.md Section 6.2; docs/adr/0014-rag-context-assembly.md,
 * docs/adr/0015-rag-grounded-generator.md).
 *
 * <p>Wires the generic {@link BudgetedContextAssembler} with the configured {@link
 * org.signalengine.rag.context.ContextBudget}, and composes the query-time {@link RagPipeline}:
 * {@link Retriever} (Task 8.3C {@code PgVectorRetriever}) then {@link ContextAssembler}, and
 * &mdash; when Task 8.5 has contributed them &mdash; the optional {@link Generator} and {@link
 * AnswerValidator} stages. {@link RagPipeline#assembleContext} always returns a real assembled
 * {@link org.signalengine.rag.context.Context} and never invokes the generator. Consumed by {@code
 * AskQuestionUseCase} (application layer) behind the {@code /api/v1/questions} endpoint.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagContextProperties.class)
class RagContextConfiguration {

  @Bean
  ContextAssembler budgetedContextAssembler(RagContextProperties properties) {
    return new BudgetedContextAssembler(properties.toBudget());
  }

  @Bean
  RagPipeline ragPipeline(
      Retriever retriever,
      ContextAssembler contextAssembler,
      ObjectProvider<Generator> generator,
      ObjectProvider<AnswerValidator> answerValidator) {
    StagedRagPipeline.Builder builder =
        StagedRagPipeline.builder().retriever(retriever).contextAssembler(contextAssembler);
    Generator configuredGenerator = generator.getIfAvailable();
    if (configuredGenerator != null) {
      builder.generator(configuredGenerator);
      AnswerValidator configuredValidator = answerValidator.getIfAvailable();
      if (configuredValidator != null) {
        builder.answerValidator(configuredValidator);
      }
    }
    return builder.build();
  }
}
