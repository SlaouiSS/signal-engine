package org.signalengine.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.InvalidInputException;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.generation.GenerationException;
import org.signalengine.rag.generation.RagAnswer;
import org.signalengine.rag.pipeline.RagPipeline;
import org.signalengine.rag.provenance.Citation;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievalResult;

class DefaultAskQuestionUseCaseTest {

  private final RagPipeline ragPipeline = mock(RagPipeline.class);
  private final DefaultAskQuestionUseCase useCase = new DefaultAskQuestionUseCase(ragPipeline);

  @Test
  void delegatesToThePipelineWithTheGivenTextAndTopK() {
    Provenance provenance =
        new Provenance(
            "feed-1", URI.create("https://example.test/a"), "Title", "doc-1", "p1", Map.of());
    RagAnswer answer =
        RagAnswer.answered(
            "The company reported higher revenue.",
            List.of(new Citation("p1", provenance, "revenue grew 10%")));
    when(ragPipeline.execute(any())).thenReturn(execution(answer));

    RagAnswer result = useCase.askQuestion("how did revenue change?", 3);

    assertThat(result).isEqualTo(answer);
    verify(ragPipeline).execute(Query.of("how did revenue change?", 3));
  }

  @Test
  void returnsTheInsufficientEvidenceOutcomeRatherThanAnError() {
    RagAnswer unanswered =
        RagAnswer.insufficientEvidence("not enough information in the knowledge base");
    when(ragPipeline.execute(any())).thenReturn(execution(unanswered));

    RagAnswer result = useCase.askQuestion("what will the stock price be tomorrow?", 5);

    assertThat(result.answered()).isFalse();
    assertThat(result.citations()).isEmpty();
    assertThat(result.text()).isEqualTo("not enough information in the knowledge base");
  }

  @Test
  void preservesCitationsAndProvenanceFromThePipelineAnswer() {
    Provenance provenance = Provenance.ofSource("feed-1");
    Citation citation = new Citation("p1", provenance, "quoted span");
    RagAnswer answer = RagAnswer.answered("Answer text.", List.of(citation));
    when(ragPipeline.execute(any())).thenReturn(execution(answer));

    RagAnswer result = useCase.askQuestion("a question", 5);

    assertThat(result.citations()).containsExactly(citation);
  }

  @Test
  void rejectsABlankQuestion() {
    assertThatThrownBy(() -> useCase.askQuestion("   ", 5))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  void propagatesGenerationFailures() {
    when(ragPipeline.execute(any()))
        .thenThrow(new GenerationException("the AI provider is unreachable"));

    assertThatThrownBy(() -> useCase.askQuestion("a question", 5))
        .isInstanceOf(GenerationException.class);
  }

  @Test
  void failsFastWhenThePipelineHasNoGeneratorConfigured() {
    RagExecution executionWithNoAnswer =
        new RagExecution(
            UUID.randomUUID(),
            Query.of("a question"),
            Query.of("a question"),
            RetrievalResult.of(List.of()),
            Context.of(List.of()),
            null,
            List.of(),
            Instant.now(),
            Instant.now(),
            Map.of());
    when(ragPipeline.execute(any())).thenReturn(executionWithNoAnswer);

    assertThatThrownBy(() -> useCase.askQuestion("a question", 5))
        .isInstanceOf(IllegalStateException.class);
  }

  private static RagExecution execution(RagAnswer answer) {
    Instant now = Instant.now();
    return new RagExecution(
        UUID.randomUUID(),
        Query.of("a question"),
        Query.of("a question"),
        RetrievalResult.of(List.of()),
        Context.of(List.of()),
        answer,
        List.of(),
        now,
        now,
        Map.of());
  }
}
