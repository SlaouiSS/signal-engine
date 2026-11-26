package org.signalengine.application.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.ai.AiInputLimits;
import org.signalengine.application.dedup.DefaultNearDuplicateAssessor;
import org.signalengine.application.dedup.NearDuplicateAssessor;
import org.signalengine.application.dedup.NearDuplicateAssessor.NearDuplicateAssessment;
import org.signalengine.application.usecase.DefaultAskQuestionUseCase;
import org.signalengine.application.usecase.DefaultSemanticSearchUseCase;
import org.signalengine.rag.pipeline.RagPipeline;
import org.signalengine.rag.retrieval.Retriever;

/**
 * Input over a known capability limit is refused locally: the AI service is never called, nothing
 * is shortened, and the diagnostic says what was too long.
 */
class AiInputLimitsTest {

  private final AiCapabilityInvoker invoker = mock(AiCapabilityInvoker.class);

  private static String text(int characters) {
    return "x".repeat(characters);
  }

  private static void assertRefusedLocally(AiError error, String diagnostic) {
    assertThat(error.rejectsThisInput()).isTrue();
    assertThat(error.retryable()).isFalse();
    assertThat(error.capabilitySuspended()).isFalse();
    assertThat(error.message()).contains(diagnostic);
  }

  @Test
  void relevanceImportanceAndNearDuplicateRefuseContentOverTheirLimit() {
    var relevance =
        new DefaultRelevanceAssessor(invoker)
            .assess(new RelevanceAssessor.Query(text(20_001), List.of(), List.of()));
    var importance =
        new DefaultImportanceAssessor(invoker)
            .assess(new ImportanceAssessor.Query(text(20_001), "reason", Set.of()));
    var nearDuplicate =
        new DefaultNearDuplicateAssessor(invoker)
            .assess(
                new NearDuplicateAssessor.Query(
                    text(20_001),
                    List.of(new NearDuplicateAssessor.Candidate(UUID.randomUUID(), "other"))));

    assertRefusedLocally(
        ((RelevanceAssessor.RelevanceVerdict.AssessmentUnavailable) relevance).error(),
        "20001 characters; the relevance capability accepts at most 20000");
    assertRefusedLocally(
        ((ImportanceAssessor.ImportanceVerdict.AssessmentUnavailable) importance).error(),
        "importance");
    assertRefusedLocally(
        ((NearDuplicateAssessment.AssessmentUnavailable) nearDuplicate).error(), "near-duplicate");
    verifyNoInteractions(invoker);
  }

  @Test
  void summarisationRefusesContentOrASourceReferenceOverItsLimit() {
    var generator = new DefaultSummaryGenerator(invoker);
    var longContent = generator.generate(new SummaryGenerator.Request(text(40_001), List.of()));
    var longUrl =
        generator.generate(
            new SummaryGenerator.Request(
                "short", List.of(new SummaryGenerator.SourceReference("name", text(2_001)))));

    assertRefusedLocally(
        ((SummaryGenerator.SummaryOutcome.GenerationUnavailable) longContent).error(),
        "the content has 40001 characters");
    assertRefusedLocally(
        ((SummaryGenerator.SummaryOutcome.GenerationUnavailable) longUrl).error(), "a source URL");
    verifyNoInteractions(invoker);
  }

  @Test
  void charactersAreCountedAsThePythonServiceCountsThem() {
    String supplementaryCharacters = "𝔸".repeat(20_000); // 40,000 UTF-16 units

    assertThat(AiInputLimits.exceeds(supplementaryCharacters, 20_000)).isFalse();
    assertThat(AiInputLimits.exceeds(supplementaryCharacters + "x", 20_000)).isTrue();
  }

  @Test
  void anOverlongQuestionOrSearchQueryIsTheCallersErrorAndReachesNoCapability() {
    RagPipeline ragPipeline = mock(RagPipeline.class);
    Retriever retriever = mock(Retriever.class);

    assertThatThrownBy(() -> new DefaultAskQuestionUseCase(ragPipeline).askQuestion(text(4_001), 5))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("4000");
    assertThatThrownBy(() -> new DefaultSemanticSearchUseCase(retriever).search(text(40_001), 5))
        .isInstanceOf(InvalidInputException.class);
    verifyNoInteractions(ragPipeline, retriever);
  }
}
