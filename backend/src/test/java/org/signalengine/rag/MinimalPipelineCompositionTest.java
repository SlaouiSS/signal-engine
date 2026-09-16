package org.signalengine.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.RagCoreDoubles.FirstPassageGenerator;
import org.signalengine.rag.RagCoreDoubles.FixedRetriever;
import org.signalengine.rag.RagCoreDoubles.OrderPreservingContextAssembler;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.pipeline.StagedRagPipeline;
import org.signalengine.rag.query.Query;

/**
 * The minimal pipeline composes {@code Retriever -> ContextAssembler}; a {@code Generator} is
 * optional.
 */
class MinimalPipelineCompositionTest {

  private final FixedRetriever retriever =
      new FixedRetriever(
          RagCoreDoubles.passage("p1", "first passage text", 0.90),
          RagCoreDoubles.passage("p2", "second passage text", 0.40));
  private final OrderPreservingContextAssembler assembler = new OrderPreservingContextAssembler();

  @Test
  void retrieverAndContextAssemblerAloneProduceAContextWithoutAnAnswer() {
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder().retriever(retriever).contextAssembler(assembler).build();

    RagExecution execution = pipeline.execute(Query.of("what happened?"));

    assertThat(execution.context().passages())
        .extracting(passage -> passage.passageId())
        .containsExactly("p1", "p2");
    assertThat(execution.generated()).isFalse();
    assertThat(execution.answer()).isNull();
    assertThat(execution.stages())
        .extracting(stage -> stage.component().type())
        .containsExactly(RagComponentType.RETRIEVER, RagComponentType.CONTEXT_ASSEMBLER);
  }

  @Test
  void addingAGeneratorAddsAnAnswerWithoutChangingTheOtherStages() {
    FirstPassageGenerator generator = new FirstPassageGenerator();

    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(assembler)
            .generator(generator)
            .build();

    RagExecution execution = pipeline.execute(Query.of("what happened?"));

    assertThat(execution.generated()).isTrue();
    assertThat(execution.answer().answered()).isTrue();
    assertThat(execution.answer().citations())
        .extracting(citation -> citation.passageId())
        .containsExactly("p1");
    assertThat(execution.stages())
        .extracting(stage -> stage.component().type())
        .containsExactly(
            RagComponentType.RETRIEVER,
            RagComponentType.CONTEXT_ASSEMBLER,
            RagComponentType.GENERATOR);
    assertThat(generator.calls).isEqualTo(1);
  }

  @Test
  void theTwoRequiredStagesMustBeSupplied() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> StagedRagPipeline.builder().contextAssembler(assembler).build())
        .isInstanceOf(NullPointerException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> StagedRagPipeline.builder().retriever(retriever).build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void executionCarriesRetrievalContextAndStagesAsIndependentlyUsableOutputs() {
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(assembler)
            .generator(new FirstPassageGenerator())
            .build();

    RagExecution execution = pipeline.execute(Query.of("what happened?"));

    List<String> retrievalIds =
        execution.retrieval().passages().stream().map(p -> p.passageId()).toList();
    List<String> contextIds =
        execution.context().passages().stream().map(p -> p.passageId()).toList();
    assertThat(retrievalIds).containsExactly("p1", "p2");
    assertThat(contextIds).containsExactly("p1", "p2");
    assertThat(execution.duration()).isNotNull();
  }
}
