package org.signalengine.application.dedup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;

/**
 * Calls the {@code near-duplicate} AI capability (contract v1) through the Task 6A invoker and maps
 * its structured result back to per-candidate {@link NearDuplicateAssessor.Verdict}s.
 *
 * <p>Plain application class: it depends only on {@link AiCapabilityInvoker}. A result that does
 * not carry exactly one verdict per candidate is treated as a non-retryable contract failure rather
 * than acted on.
 */
public final class DefaultNearDuplicateAssessor implements NearDuplicateAssessor {

  static final String CAPABILITY = "near-duplicate";
  static final int CONTRACT_VERSION = 1;

  private final AiCapabilityInvoker aiCapabilityInvoker;

  public DefaultNearDuplicateAssessor(AiCapabilityInvoker aiCapabilityInvoker) {
    this.aiCapabilityInvoker = aiCapabilityInvoker;
  }

  @Override
  public NearDuplicateAssessment assess(Query query) {
    Map<String, UUID> candidateIds = new LinkedHashMap<>();
    List<Comparison> comparisons =
        query.candidates().stream()
            .map(
                candidate -> {
                  String id = candidate.rawInformationItemId().toString();
                  candidateIds.put(id, candidate.rawInformationItemId());
                  return new Comparison(id, candidate.text());
                })
            .toList();

    var request =
        AiCapabilityRequest.of(
            CAPABILITY,
            CONTRACT_VERSION,
            new RequestPayload(new CandidateText(query.candidateText()), comparisons));

    return switch (aiCapabilityInvoker.invoke(request, ResultPayload.class)) {
      case Failed<ResultPayload> failed ->
          new NearDuplicateAssessment.AssessmentUnavailable(failed.error());
      case Produced<ResultPayload> produced ->
          toAssessment(produced.result(), candidateIds, produced.correlationId());
    };
  }

  private NearDuplicateAssessment toAssessment(
      ResultPayload result, Map<String, UUID> candidateIds, UUID correlationId) {
    List<Assessment> assessments = result == null ? null : result.assessments();
    if (assessments == null || assessments.size() != candidateIds.size()) {
      return contractViolation(correlationId, "expected one verdict per candidate");
    }
    List<Verdict> verdicts =
        assessments.stream()
            .map(
                assessment -> {
                  UUID rawItemId = candidateIds.get(assessment.id());
                  return rawItemId == null
                      ? null
                      : new Verdict(
                          rawItemId, assessment.sameUnderlyingStory(), assessment.reason());
                })
            .toList();
    if (verdicts.contains(null) || verdicts.size() != candidateIds.size()) {
      return contractViolation(correlationId, "the AI returned a verdict for an unknown candidate");
    }
    return new NearDuplicateAssessment.Assessed(verdicts);
  }

  private static NearDuplicateAssessment contractViolation(UUID correlationId, String message) {
    return new NearDuplicateAssessment.AssessmentUnavailable(
        new AiError("AI_CONTRACT_VIOLATION", "contract", false, message, correlationId, Map.of()));
  }

  // --- wire payloads (mirror agents/app/capabilities/near_duplicate.py) ---

  record RequestPayload(CandidateText candidate, List<Comparison> comparisons) {}

  record CandidateText(String text) {}

  record Comparison(String id, String text) {}

  record ResultPayload(List<Assessment> assessments) {}

  record Assessment(String id, boolean sameUnderlyingStory, String reason) {}
}
