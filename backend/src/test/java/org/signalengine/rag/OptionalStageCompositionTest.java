package org.signalengine.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.signalengine.rag.RagCoreDoubles.FirstPassageGenerator;
import org.signalengine.rag.RagCoreDoubles.FixedRetriever;
import org.signalengine.rag.RagCoreDoubles.HeadContextRefiner;
import org.signalengine.rag.RagCoreDoubles.MarkerQueryProcessor;
import org.signalengine.rag.RagCoreDoubles.OrderPreservingContextAssembler;
import org.signalengine.rag.RagCoreDoubles.ReversingReranker;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.pipeline.StagedRagPipeline;
import org.signalengine.rag.query.Query;

/**
 * Each optional stage can be inserted before or after the required ones without changing the
 * unrelated stages, and {@link StagedRagPipeline#assembleContext} returns a context with no
 * generation.
 */
class OptionalStageCompositionTest {

  private final FixedRetriever retriever =
      new FixedRetriever(
          RagCoreDoubles.passage("p1", "alpha", 0.9),
          RagCoreDoubles.passage("p2", "bravo", 0.5),
          RagCoreDoubles.passage("p3", "charlie", 0.1));
  private final OrderPreservingContextAssembler assembler = new OrderPreservingContextAssembler();

  @Test
  void aQueryProcessorIsInsertedBeforeRetrievalWithoutTouchingLaterStages() {
    StagedRagPipeline base =
        StagedRagPipeline.builder().retriever(retriever).contextAssembler(assembler).build();
    RagExecution withoutProcessor = base.execute(Query.of("headline"));

    StagedRagPipeline withProcessor =
        StagedRagPipeline.builder()
            .queryProcessor(new MarkerQueryProcessor())
            .retriever(retriever)
            .contextAssembler(assembler)
            .build();
    RagExecution withProcessorRun = withProcessor.execute(Query.of("headline"));

    assertThat(withoutProcessor.effectiveQuery().text()).isEqualTo("headline");
    assertThat(withProcessorRun.originalQuery().text()).isEqualTo("headline");
    assertThat(withProcessorRun.effectiveQuery().text())
        .isEqualTo("headline" + MarkerQueryProcessor.MARKER);
    assertThat(withProcessorRun.stages())
        .extracting(stage -> stage.component().type())
        .containsExactly(
            RagComponentType.QUERY_PROCESSOR,
            RagComponentType.RETRIEVER,
            RagComponentType.CONTEXT_ASSEMBLER);
    // the context passages are still what the (unchanged) assembler produced, in retrieval order
    assertThat(withProcessorRun.context().passages())
        .extracting(passage -> passage.passageId())
        .containsExactly("p1", "p2", "p3");
  }

  @Test
  void aRerankerIsInsertedBetweenRetrievalAndAssemblyWithoutChangingEither() {
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .reranker(new ReversingReranker())
            .contextAssembler(assembler)
            .build();

    RagExecution execution = pipeline.execute(Query.of("headline"));

    // retrieval output is unchanged; the reranker reordered what assembly then received
    assertThat(execution.retrieval().passages())
        .extracting(passage -> passage.passageId())
        .containsExactly("p3", "p2", "p1");
    assertThat(execution.context().passages())
        .extracting(passage -> passage.passageId())
        .containsExactly("p3", "p2", "p1");
    assertThat(execution.stages())
        .extracting(stage -> stage.component().type())
        .containsExactly(
            RagComponentType.RETRIEVER,
            RagComponentType.RERANKER,
            RagComponentType.CONTEXT_ASSEMBLER);
  }

  @Test
  void aContextRefinerIsInsertedAfterAssemblyWithoutChangingTheAssembler() {
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(assembler)
            .contextRefiner(new HeadContextRefiner())
            .generator(new FirstPassageGenerator())
            .build();

    RagExecution execution = pipeline.execute(Query.of("headline"));

    assertThat(execution.context().passages())
        .extracting(passage -> passage.passageId())
        .containsExactly("p1");
    assertThat(execution.stages())
        .extracting(stage -> stage.component().type())
        .containsExactly(
            RagComponentType.RETRIEVER,
            RagComponentType.CONTEXT_ASSEMBLER,
            RagComponentType.CONTEXT_REFINER,
            RagComponentType.GENERATOR);
  }

  @Test
  void assembleContextReturnsContextAndNeverInvokesAConfiguredGenerator() {
    FirstPassageGenerator generator = new FirstPassageGenerator();
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(assembler)
            .generator(generator)
            .build();

    Context context = pipeline.assembleContext(Query.of("headline"));

    assertThat(context.passages())
        .extracting(passage -> passage.passageId())
        .containsExactly("p1", "p2", "p3");
    assertThat(generator.calls).isZero();
  }
}
