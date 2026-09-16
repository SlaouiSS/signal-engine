package org.signalengine.application.signal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.signal.ImportanceAssessor.ImportanceVerdict.Assessed;
import org.signalengine.application.signal.ImportanceAssessor.ImportanceVerdict.AssessmentUnavailable;

/**
 * Calls the {@code importance} AI capability (contract v1) through the Task 6A invoker and maps its
 * structured result to an {@link ImportanceAssessor.ImportanceVerdict}. An "important-enough"
 * verdict with no reason is a non-retryable contract violation.
 */
public final class DefaultImportanceAssessor implements ImportanceAssessor {

  static final String CAPABILITY = "importance";
  static final int CONTRACT_VERSION = 1;

  private final AiCapabilityInvoker aiCapabilityInvoker;

  public DefaultImportanceAssessor(AiCapabilityInvoker aiCapabilityInvoker) {
    this.aiCapabilityInvoker = aiCapabilityInvoker;
  }

  @Override
  public ImportanceVerdict assess(Query query) {
    var payload =
        new RequestPayload(
            new ItemText(query.itemText()),
            new RelevanceContext(query.relevanceReason(), List.copyOf(query.matchedAreaCodes())));
    var request = AiCapabilityRequest.of(CAPABILITY, CONTRACT_VERSION, payload);

    return switch (aiCapabilityInvoker.invoke(request, ResultPayload.class)) {
      case Failed<ResultPayload> failed -> new AssessmentUnavailable(failed.error());
      case Produced<ResultPayload> produced ->
          toVerdict(produced.result(), produced.correlationId());
    };
  }

  private ImportanceVerdict toVerdict(ResultPayload result, UUID correlationId) {
    if (result == null || result.reason() == null) {
      return contractViolation(correlationId, "importance result was empty");
    }
    if (result.importantEnough() && result.reason().isBlank()) {
      return contractViolation(correlationId, "an important-enough verdict must have a reason");
    }
    return new Assessed(result.importantEnough(), result.reason());
  }

  private static ImportanceVerdict contractViolation(UUID correlationId, String message) {
    return new AssessmentUnavailable(
        new AiError("AI_CONTRACT_VIOLATION", "contract", false, message, correlationId, Map.of()));
  }

  // --- wire payloads (mirror agents/app/capabilities/importance.py) ---

  record RequestPayload(ItemText item, RelevanceContext relevanceContext) {}

  record ItemText(String text) {}

  record RelevanceContext(String reason, List<String> areaCodes) {}

  record ResultPayload(boolean importantEnough, String reason) {}
}
