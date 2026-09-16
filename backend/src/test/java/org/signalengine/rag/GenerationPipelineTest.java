package org.signalengine.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.RagCoreDoubles.FailingGenerator;
import org.signalengine.rag.RagCoreDoubles.FirstPassageGenerator;
import org.signalengine.rag.RagCoreDoubles.FixedRetriever;
import org.signalengine.rag.context.BudgetedContextAssembler;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.generation.GenerationException;
import org.signalengine.rag.generation.GroundingAnswerValidator;
import org.signalengine.rag.generation.RagAnswer;
import org.signalengine.rag.pipeline.StagedRagPipeline;
import org.signalengine.rag.query.Query;

/**
 * The generator is an optional stage: {@code execute} runs it (and the grounding validator) when
 * configured and skips it otherwise; {@code assembleContext} never runs it; a generation failure
 * propagates from {@code execute}.
 */
class GenerationPipelineTest {

  private final FixedRetriever retriever =
      new FixedRetriever(
          RagCoreDoubles.passage("p1", "first passage text", 0.90),
          RagCoreDoubles.passage("p2", "second passage text", 0.40));
  private final BudgetedContextAssembler assembler = new BudgetedContextAssembler();
  private final GroundingAnswerValidator groundingValidator = new GroundingAnswerValidator();

  @Test
  void executeRunsRetrievalAssemblyGenerationAndValidationInOrder() {
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(assembler)
            .generator(new FirstPassageGenerator())
            .answerValidator(groundingValidator)
            .build();

    RagExecution execution = pipeline.execute(Query.of("what happened?"));

    assertThat(execution.generated()).isTrue();
    assertThat(execution.answer().answered()).isTrue();
    assertThat(execution.answer().citations()).extracting(c -> c.passageId()).containsExactly("p1");
    assertThat(execution.answer().metadata())
        .containsEntry(GroundingAnswerValidator.META_VALIDATOR, "grounding-answer-validator");
    assertThat(execution.stages())
        .extracting(stage -> stage.component().type())
        .containsExactly(
            RagComponentType.RETRIEVER,
            RagComponentType.CONTEXT_ASSEMBLER,
            RagComponentType.GENERATOR,
            RagComponentType.ANSWER_VALIDATOR);
  }

  @Test
  void theGenerationStageRecordsDurationAndAnswerState() {
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(assembler)
            .generator(new FirstPassageGenerator())
            .build();

    RagExecution execution = pipeline.execute(Query.of("what happened?"));

    var generationStage =
        execution.stages().stream()
            .filter(stage -> stage.component().type() == RagComponentType.GENERATOR)
            .findFirst()
            .orElseThrow();
    assertThat(generationStage.notes())
        .containsKey("durationMillis")
        .containsEntry("answered", "true");
  }

  @Test
  void withoutAGeneratorExecuteStillProducesRetrievalAndContextButNoAnswer() {
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder().retriever(retriever).contextAssembler(assembler).build();

    RagExecution execution = pipeline.execute(Query.of("what happened?"));

    assertThat(execution.generated()).isFalse();
    assertThat(execution.answer()).isNull();
    assertThat(execution.context().passages()).isNotEmpty();
    assertThat(execution.stages())
        .extracting(stage -> stage.component().type())
        .containsExactly(RagComponentType.RETRIEVER, RagComponentType.CONTEXT_ASSEMBLER);
  }

  @Test
  void assembleContextNeverInvokesTheGenerator() {
    FirstPassageGenerator generator = new FirstPassageGenerator();
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(assembler)
            .generator(generator)
            .answerValidator(groundingValidator)
            .build();

    pipeline.assembleContext(Query.of("what happened?"));

    assertThat(generator.calls).isZero();
  }

  @Test
  void aGenerationFailurePropagatesFromExecute() {
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(assembler)
            .generator(new FailingGenerator())
            .answerValidator(groundingValidator)
            .build();

    assertThatThrownBy(() -> pipeline.execute(Query.of("what happened?")))
        .isInstanceOf(GenerationException.class);
  }

  @Test
  void theGroundingValidatorDowngradesAnAnswerThatCitesOutsideTheContext() {
    // a generator that cites a passage id the context does not contain
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(assembler)
            .generator(
                (query, context) ->
                    RagAnswer.answered(
                        "an answer resting on nothing supplied",
                        List.of(
                            new org.signalengine.rag.provenance.Citation(
                                "not-in-context",
                                org.signalengine.rag.provenance.Provenance.ofSource("x"),
                                null))))
            .answerValidator(groundingValidator)
            .build();

    RagExecution execution = pipeline.execute(Query.of("what happened?"));

    assertThat(execution.answer().answered()).isFalse();
    assertThat(execution.answer().citations()).isEmpty();
    assertThat(execution.answer().metadata())
        .containsEntry(GroundingAnswerValidator.META_DOWNGRADED, "true");
  }

  @Test
  void answerValidatorRequiresAGenerator() {
    assertThatThrownBy(
            () ->
                StagedRagPipeline.builder()
                    .retriever(retriever)
                    .contextAssembler(assembler)
                    .answerValidator(groundingValidator)
                    .build())
        .isInstanceOf(IllegalStateException.class);
  }
}
