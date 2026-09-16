package org.signalengine.application.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.ai.AiResponseMetadata;
import org.signalengine.application.dedup.DefaultNearDuplicateAssessor.Assessment;
import org.signalengine.application.dedup.DefaultNearDuplicateAssessor.RequestPayload;
import org.signalengine.application.dedup.DefaultNearDuplicateAssessor.ResultPayload;
import org.signalengine.application.dedup.NearDuplicateAssessor.Candidate;
import org.signalengine.application.dedup.NearDuplicateAssessor.NearDuplicateAssessment.Assessed;
import org.signalengine.application.dedup.NearDuplicateAssessor.NearDuplicateAssessment.AssessmentUnavailable;
import org.signalengine.application.dedup.NearDuplicateAssessor.Query;

class DefaultNearDuplicateAssessorTest {

  private final AiCapabilityInvoker invoker = mock(AiCapabilityInvoker.class);
  private final DefaultNearDuplicateAssessor assessor = new DefaultNearDuplicateAssessor(invoker);

  private final UUID candidateA = UUID.randomUUID();
  private final UUID candidateB = UUID.randomUUID();

  private Query twoCandidateQuery() {
    return new Query(
        "the central bank raised its rate today",
        List.of(
            new Candidate(candidateA, "policymakers lifted the key rate today"),
            new Candidate(candidateB, "a startup-friendly city ranking was published")));
  }

  @SuppressWarnings("unchecked")
  private void invokerReturns(AiCapabilityOutcome<ResultPayload> outcome) {
    when(invoker.invoke(any(AiCapabilityRequest.class), eq(ResultPayload.class)))
        .thenReturn(outcome);
  }

  @Test
  void callsTheNearDuplicateCapabilityWithTheCandidateAndComparisons() {
    invokerReturns(
        produced(
            new ResultPayload(
                List.of(
                    new Assessment(candidateA.toString(), true, "same rate rise"),
                    new Assessment(candidateB.toString(), false, "unrelated")))));

    assessor.assess(twoCandidateQuery());

    ArgumentCaptor<AiCapabilityRequest> request =
        ArgumentCaptor.forClass(AiCapabilityRequest.class);
    org.mockito.Mockito.verify(invoker).invoke(request.capture(), eq(ResultPayload.class));
    assertThat(request.getValue().capability()).isEqualTo("near-duplicate");
    assertThat(request.getValue().contractVersion()).isEqualTo(1);
    RequestPayload payload = (RequestPayload) request.getValue().payload();
    assertThat(payload.candidate().text()).isEqualTo("the central bank raised its rate today");
    assertThat(payload.comparisons())
        .extracting("id")
        .containsExactly(candidateA.toString(), candidateB.toString());
  }

  @Test
  void mapsAProducedResultToPerCandidateVerdicts() {
    invokerReturns(
        produced(
            new ResultPayload(
                List.of(
                    new Assessment(candidateB.toString(), false, "unrelated"),
                    new Assessment(candidateA.toString(), true, "same rate rise")))));

    Assessed assessed = (Assessed) assessor.assess(twoCandidateQuery());

    assertThat(assessed.verdicts())
        .anySatisfy(
            v -> {
              assertThat(v.rawInformationItemId()).isEqualTo(candidateA);
              assertThat(v.sameUnderlyingStory()).isTrue();
            })
        .anySatisfy(
            v -> {
              assertThat(v.rawInformationItemId()).isEqualTo(candidateB);
              assertThat(v.sameUnderlyingStory()).isFalse();
            });
  }

  @Test
  void mapsATypedAiFailureToAssessmentUnavailable() {
    AiError error =
        new AiError("AI_PROVIDER_TIMEOUT", "timeout", true, "slow", UUID.randomUUID(), Map.of());
    invokerReturns(new AiCapabilityOutcome.Failed<>(error));

    AssessmentUnavailable unavailable =
        (AssessmentUnavailable) assessor.assess(twoCandidateQuery());

    assertThat(unavailable.error().retryable()).isTrue();
    assertThat(unavailable.error().code()).isEqualTo("AI_PROVIDER_TIMEOUT");
  }

  @Test
  void treatsAMissingVerdictAsANonRetryableContractViolation() {
    invokerReturns(
        produced(new ResultPayload(List.of(new Assessment(candidateA.toString(), true, "same")))));

    AssessmentUnavailable unavailable =
        (AssessmentUnavailable) assessor.assess(twoCandidateQuery());

    assertThat(unavailable.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
    assertThat(unavailable.error().retryable()).isFalse();
  }

  @Test
  void treatsAVerdictForAnUnknownCandidateAsAContractViolation() {
    invokerReturns(
        produced(
            new ResultPayload(
                List.of(
                    new Assessment(candidateA.toString(), true, "same"),
                    new Assessment(UUID.randomUUID().toString(), false, "who?")))));

    AssessmentUnavailable unavailable =
        (AssessmentUnavailable) assessor.assess(twoCandidateQuery());

    assertThat(unavailable.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
  }

  private static AiCapabilityOutcome<ResultPayload> produced(ResultPayload result) {
    return new AiCapabilityOutcome.Produced<>(
        result, new AiResponseMetadata("fake", "fake", "near-duplicate/v1", 1L), UUID.randomUUID());
  }
}
