package org.signalengine.application.signal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.signal.SummaryGenerator.SummaryOutcome.Generated;
import org.signalengine.application.signal.SummaryGenerator.SummaryOutcome.GenerationUnavailable;

/**
 * Calls the {@code summarize} AI capability (contract v1) through the Task 6A invoker and maps its
 * structured result to a {@link SummaryGenerator.SummaryOutcome}. A missing summary or grounding
 * note is a non-retryable contract violation — never a fabricated Summary.
 */
public final class DefaultSummaryGenerator implements SummaryGenerator {

  static final String CAPABILITY = "summarize";
  static final int CONTRACT_VERSION = 1;

  private final AiCapabilityInvoker aiCapabilityInvoker;

  public DefaultSummaryGenerator(AiCapabilityInvoker aiCapabilityInvoker) {
    this.aiCapabilityInvoker = aiCapabilityInvoker;
  }

  @Override
  public SummaryOutcome generate(Request request) {
    var payload =
        new RequestPayload(
            request.content(),
            request.sources().stream().map(s -> new Source(s.name(), s.url())).toList());
    var invocation = AiCapabilityRequest.of(CAPABILITY, CONTRACT_VERSION, payload);

    return switch (aiCapabilityInvoker.invoke(invocation, ResultPayload.class)) {
      case Failed<ResultPayload> failed -> new GenerationUnavailable(failed.error());
      case Produced<ResultPayload> produced ->
          toOutcome(produced.result(), produced.correlationId());
    };
  }

  private SummaryOutcome toOutcome(ResultPayload result, UUID correlationId) {
    if (result == null
        || result.summary() == null
        || result.summary().isBlank()
        || result.groundingNotes() == null
        || result.groundingNotes().isBlank()) {
      return contractViolation(correlationId, "summary result was missing the summary or notes");
    }
    return new Generated(result.summary(), result.groundingNotes());
  }

  private static SummaryOutcome contractViolation(UUID correlationId, String message) {
    return new GenerationUnavailable(
        new AiError("AI_CONTRACT_VIOLATION", "contract", false, message, correlationId, Map.of()));
  }

  // --- wire payloads (mirror agents/app/capabilities/summarize.py) ---

  record RequestPayload(String content, List<Source> sources) {}

  record Source(String name, String url) {}

  record ResultPayload(String summary, String groundingNotes) {}
}
