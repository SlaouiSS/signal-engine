package org.signalengine.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.signalengine.rag.RagCoreDoubles.FixedRetriever;
import org.signalengine.rag.context.BudgetedContextAssembler;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.context.ContextBudget;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.pipeline.StagedRagPipeline;
import org.signalengine.rag.query.Query;

/**
 * {@link StagedRagPipeline#assembleContext} works with the real {@link BudgetedContextAssembler}
 * behind the {@code Retriever -> ContextAssembler} contract, no generator involved.
 */
class ContextAssemblyPipelineTest {

  private final FixedRetriever retriever =
      new FixedRetriever(
          RagCoreDoubles.passage("p1", "x".repeat(40), 0.90),
          RagCoreDoubles.passage("p2", "x".repeat(40), 0.50),
          RagCoreDoubles.passage("p3", "x".repeat(40), 0.10));

  @Test
  void assembleContextRunsRetrievalThenTheBudgetedAssemblerAndStopsThere() {
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(new BudgetedContextAssembler(ContextBudget.ofCharacters(100)))
            .build();

    Context context = pipeline.assembleContext(Query.of("what happened?"));

    // budget 100, three 40-char passages -> first two fit (80), third skipped
    assertThat(context.passages())
        .extracting(passage -> passage.passageId())
        .containsExactly("p1", "p2");
    assertThat(context.metadata())
        .containsEntry(BudgetedContextAssembler.META_SKIPPED_FOR_BUDGET, "1");
    assertThat(retriever.calls).isEqualTo(1);
  }

  @Test
  void executeWithoutAGeneratorCarriesRetrievalAndContextButNoAnswer() {
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(new BudgetedContextAssembler())
            .build();

    RagExecution execution = pipeline.execute(Query.of("what happened?"));

    assertThat(execution.retrieval().passages()).hasSize(3);
    assertThat(execution.context().passages()).hasSize(3);
    assertThat(execution.generated()).isFalse();
    assertThat(execution.stages())
        .extracting(stage -> stage.component().type())
        .containsExactly(RagComponentType.RETRIEVER, RagComponentType.CONTEXT_ASSEMBLER);
  }
}
