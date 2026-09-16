package org.signalengine.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The wire records for the capability contract (docs/adr/0006-ai-java-python-foundation.md). They
 * mirror the Pydantic models in {@code agents/app/contract.py}, which are the source of truth; the
 * contract test replays {@code agents/contract/examples/*.json} through these records.
 *
 * <p>Responses tolerate unknown fields so an additive contract change (a new minor version) does
 * not break an older client (docs/03-technical-spec.md Section 8.4).
 */
final class AiCapabilityEnvelopes {

  private AiCapabilityEnvelopes() {}

  /** Request body. Field order and names are the contract. */
  record RequestEnvelope(
      String correlationId, int contractVersion, String capability, Object payload) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ResponseEnvelope(
      String correlationId,
      Integer contractVersion,
      String capability,
      String status,
      JsonNode result,
      ErrorBody error,
      ResponseMeta meta) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ErrorBody(
      String code,
      String category,
      Boolean retryable,
      String message,
      String correlationId,
      Map<String, Object> details) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ResponseMeta(String provider, String model, String promptVersion, Long durationMillis) {}
}
