package org.signalengine.application.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.ai.AiResponseMetadata;
import org.signalengine.application.signal.ImportanceAssessor.ImportanceVerdict;
import org.signalengine.application.signal.RelevanceAssessor.AreaContext;
import org.signalengine.application.signal.RelevanceAssessor.InterestContext;
import org.signalengine.application.signal.RelevanceAssessor.RelevanceVerdict;
import org.signalengine.application.signal.SummaryGenerator.SourceReference;
import org.signalengine.application.signal.SummaryGenerator.SummaryOutcome;

/** Unit tests for the three thin AI ports over the Task 6A invoker. */
class SignalPipelineAssessorsTest {

  private final AiCapabilityInvoker invoker = mock(AiCapabilityInvoker.class);

  private static <R> AiCapabilityOutcome<R> produced(R result) {
    return new AiCapabilityOutcome.Produced<>(
        result, new AiResponseMetadata("fake", "fake", "v1", 1L), UUID.randomUUID());
  }

  private static <R> AiCapabilityOutcome<R> failed(String code, boolean retryable) {
    return new AiCapabilityOutcome.Failed<>(
        new AiError(code, "provider", retryable, "boom", UUID.randomUUID(), Map.of()));
  }

  @Nested
  class Relevance {

    private final DefaultRelevanceAssessor assessor = new DefaultRelevanceAssessor(invoker);
    private final UUID interestId = UUID.randomUUID();

    private RelevanceAssessor.Query query() {
      return new RelevanceAssessor.Query(
          "the EU adopted the AI Act",
          List.of(
              new AreaContext("LAW_AND_REGULATION", "Law"),
              new AreaContext("AI_AND_TECHNOLOGY", "AI")),
          List.of(new InterestContext(interestId, "LAW_AND_REGULATION", "EU AI rules")));
    }

    @SuppressWarnings("unchecked")
    private void invokerReturns(
        AiCapabilityOutcome<DefaultRelevanceAssessor.ResultPayload> outcome) {
      when(invoker.invoke(
              any(AiCapabilityRequest.class), eq(DefaultRelevanceAssessor.ResultPayload.class)))
          .thenReturn(outcome);
    }

    @Test
    void mapsARelevantVerdictAndResolvesInterestIds() {
      invokerReturns(
          produced(
              new DefaultRelevanceAssessor.ResultPayload(
                  true,
                  "matches EU AI regulation",
                  List.of("LAW_AND_REGULATION"),
                  List.of(interestId.toString()))));

      RelevanceVerdict.Assessed assessed = (RelevanceVerdict.Assessed) assessor.assess(query());

      assertThat(assessed.relevant()).isTrue();
      assertThat(assessed.matchedAreaCodes()).containsExactly("LAW_AND_REGULATION");
      assertThat(assessed.matchedInterestIds()).containsExactly(interestId);
    }

    @Test
    void sendsTheItemAreasAndInterestsToTheCapability() {
      invokerReturns(
          produced(new DefaultRelevanceAssessor.ResultPayload(false, "no", List.of(), List.of())));

      assessor.assess(query());

      ArgumentCaptor<AiCapabilityRequest> request =
          ArgumentCaptor.forClass(AiCapabilityRequest.class);
      verify(invoker).invoke(request.capture(), eq(DefaultRelevanceAssessor.ResultPayload.class));
      assertThat(request.getValue().capability()).isEqualTo("relevance");
      var payload = (DefaultRelevanceAssessor.RequestPayload) request.getValue().payload();
      assertThat(payload.item().text()).isEqualTo("the EU adopted the AI Act");
      assertThat(payload.areas())
          .extracting("code")
          .containsExactly("LAW_AND_REGULATION", "AI_AND_TECHNOLOGY");
    }

    @Test
    void aMatchedAreaOutsideTheCatalogueIsANonRetryableContractViolation() {
      invokerReturns(
          produced(
              new DefaultRelevanceAssessor.ResultPayload(
                  true, "r", List.of("NOT_A_REAL_AREA"), List.of())));

      var unavailable = (RelevanceVerdict.AssessmentUnavailable) assessor.assess(query());
      assertThat(unavailable.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
      assertThat(unavailable.error().retryable()).isFalse();
    }

    @Test
    void aRelevantVerdictWithNoAreaIsAContractViolation() {
      invokerReturns(
          produced(new DefaultRelevanceAssessor.ResultPayload(true, "r", List.of(), List.of())));

      var unavailable = (RelevanceVerdict.AssessmentUnavailable) assessor.assess(query());
      assertThat(unavailable.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
    }

    @Test
    void aRelevantVerdictWithAnEmptyReasonIsANonRetryableContractViolation() {
      invokerReturns(
          produced(
              new DefaultRelevanceAssessor.ResultPayload(
                  true, "", List.of("LAW_AND_REGULATION"), List.of())));

      var unavailable = (RelevanceVerdict.AssessmentUnavailable) assessor.assess(query());
      assertThat(unavailable.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
      assertThat(unavailable.error().retryable()).isFalse();
    }

    @Test
    void aRelevantVerdictWithAWhitespaceOnlyReasonIsANonRetryableContractViolation() {
      invokerReturns(
          produced(
              new DefaultRelevanceAssessor.ResultPayload(
                  true, "   ", List.of("LAW_AND_REGULATION"), List.of())));

      var unavailable = (RelevanceVerdict.AssessmentUnavailable) assessor.assess(query());
      assertThat(unavailable.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
      assertThat(unavailable.error().retryable()).isFalse();
    }

    @Test
    void aRelevantVerdictWithANullReasonIsANonRetryableContractViolation() {
      invokerReturns(
          produced(
              new DefaultRelevanceAssessor.ResultPayload(
                  true, null, List.of("LAW_AND_REGULATION"), List.of())));

      var unavailable = (RelevanceVerdict.AssessmentUnavailable) assessor.assess(query());
      assertThat(unavailable.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
      assertThat(unavailable.error().retryable()).isFalse();
    }

    @Test
    void aNotRelevantVerdictWithABlankReasonIsStillAccepted() {
      invokerReturns(
          produced(new DefaultRelevanceAssessor.ResultPayload(false, "  ", List.of(), List.of())));

      RelevanceVerdict.Assessed assessed = (RelevanceVerdict.Assessed) assessor.assess(query());
      assertThat(assessed.relevant()).isFalse();
      assertThat(assessed.matchedAreaCodes()).isEmpty();
    }

    @Test
    void aTypedAiFailureBecomesAssessmentUnavailable() {
      invokerReturns(failed("AI_PROVIDER_TIMEOUT", true));

      var unavailable = (RelevanceVerdict.AssessmentUnavailable) assessor.assess(query());
      assertThat(unavailable.error().retryable()).isTrue();
    }
  }

  @Nested
  class Importance {

    private final DefaultImportanceAssessor assessor = new DefaultImportanceAssessor(invoker);

    private ImportanceAssessor.Query query() {
      return new ImportanceAssessor.Query(
          "a major rate rise", "monetary policy", Set.of("MARKETS_AND_INVESTMENT"));
    }

    @SuppressWarnings("unchecked")
    private void invokerReturns(
        AiCapabilityOutcome<DefaultImportanceAssessor.ResultPayload> outcome) {
      when(invoker.invoke(
              any(AiCapabilityRequest.class), eq(DefaultImportanceAssessor.ResultPayload.class)))
          .thenReturn(outcome);
    }

    @Test
    void mapsAnImportantVerdict() {
      invokerReturns(produced(new DefaultImportanceAssessor.ResultPayload(true, "big move")));

      var assessed = (ImportanceVerdict.Assessed) assessor.assess(query());
      assertThat(assessed.importantEnough()).isTrue();
      assertThat(assessed.reason()).isEqualTo("big move");
    }

    @Test
    void anImportantVerdictWithoutReasonIsAContractViolation() {
      invokerReturns(produced(new DefaultImportanceAssessor.ResultPayload(true, "  ")));

      var unavailable = (ImportanceVerdict.AssessmentUnavailable) assessor.assess(query());
      assertThat(unavailable.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
      assertThat(unavailable.error().retryable()).isFalse();
    }

    @Test
    void sendsTheRelevanceContext() {
      invokerReturns(produced(new DefaultImportanceAssessor.ResultPayload(false, "minor")));

      assessor.assess(query());

      ArgumentCaptor<AiCapabilityRequest> request =
          ArgumentCaptor.forClass(AiCapabilityRequest.class);
      verify(invoker).invoke(request.capture(), eq(DefaultImportanceAssessor.ResultPayload.class));
      var payload = (DefaultImportanceAssessor.RequestPayload) request.getValue().payload();
      assertThat(payload.relevanceContext().reason()).isEqualTo("monetary policy");
    }

    @Test
    void aTypedAiFailureBecomesAssessmentUnavailable() {
      invokerReturns(failed("AI_TRANSPORT_ERROR", true));
      assertThat(assessor.assess(query()))
          .isInstanceOf(ImportanceVerdict.AssessmentUnavailable.class);
    }
  }

  @Nested
  class Summarising {

    private final DefaultSummaryGenerator generator = new DefaultSummaryGenerator(invoker);

    private SummaryGenerator.Request request() {
      return new SummaryGenerator.Request(
          "Acme will buy Beta for 1.2bn euros.",
          List.of(new SourceReference("Newswire", "https://ex.test/acme")));
    }

    @SuppressWarnings("unchecked")
    private void invokerReturns(
        AiCapabilityOutcome<DefaultSummaryGenerator.ResultPayload> outcome) {
      when(invoker.invoke(
              any(AiCapabilityRequest.class), eq(DefaultSummaryGenerator.ResultPayload.class)))
          .thenReturn(outcome);
    }

    @Test
    void mapsAGeneratedSummary() {
      invokerReturns(
          produced(
              new DefaultSummaryGenerator.ResultPayload("Acme buys Beta.", "from the content")));

      var generated = (SummaryOutcome.Generated) generator.generate(request());
      assertThat(generated.summaryText()).isEqualTo("Acme buys Beta.");
      assertThat(generated.groundingNotes()).isEqualTo("from the content");
    }

    @Test
    void aMissingGroundingNoteIsANonRetryableContractViolation() {
      invokerReturns(produced(new DefaultSummaryGenerator.ResultPayload("Acme buys Beta.", " ")));

      var unavailable = (SummaryOutcome.GenerationUnavailable) generator.generate(request());
      assertThat(unavailable.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
      assertThat(unavailable.error().retryable()).isFalse();
    }

    @Test
    void sendsTheContentAndSources() {
      invokerReturns(produced(new DefaultSummaryGenerator.ResultPayload("s", "n")));

      generator.generate(request());

      ArgumentCaptor<AiCapabilityRequest> req = ArgumentCaptor.forClass(AiCapabilityRequest.class);
      verify(invoker).invoke(req.capture(), eq(DefaultSummaryGenerator.ResultPayload.class));
      var payload = (DefaultSummaryGenerator.RequestPayload) req.getValue().payload();
      assertThat(payload.content()).contains("Acme");
      assertThat(payload.sources())
          .singleElement()
          .satisfies(s -> assertThat(s.name()).isEqualTo("Newswire"));
    }

    @Test
    void aTypedAiFailureBecomesGenerationUnavailable() {
      invokerReturns(failed("AI_PROVIDER_UNAVAILABLE", true));
      assertThat(generator.generate(request()))
          .isInstanceOf(SummaryOutcome.GenerationUnavailable.class);
    }
  }
}
