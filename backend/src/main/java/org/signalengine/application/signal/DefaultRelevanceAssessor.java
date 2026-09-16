package org.signalengine.application.signal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.signal.RelevanceAssessor.RelevanceVerdict.Assessed;
import org.signalengine.application.signal.RelevanceAssessor.RelevanceVerdict.AssessmentUnavailable;

/**
 * Calls the {@code relevance} AI capability (contract v1) through the Task 6A invoker and maps its
 * structured result to a {@link RelevanceAssessor.RelevanceVerdict}. A matched code/id the query
 * did not supply, or a "relevant" verdict with no reason or no matched area, is a non-retryable
 * contract violation rather than acted on (docs/adr/0008-relevance-importance-signal-summary.md).
 */
public final class DefaultRelevanceAssessor implements RelevanceAssessor {

  static final String CAPABILITY = "relevance";
  static final int CONTRACT_VERSION = 1;

  private final AiCapabilityInvoker aiCapabilityInvoker;

  public DefaultRelevanceAssessor(AiCapabilityInvoker aiCapabilityInvoker) {
    this.aiCapabilityInvoker = aiCapabilityInvoker;
  }

  @Override
  public RelevanceVerdict assess(Query query) {
    var payload =
        new RequestPayload(
            new ItemText(query.itemText()),
            query.areas().stream().map(a -> new Area(a.code(), a.name())).toList(),
            query.interests().stream()
                .map(i -> new Interest(i.id().toString(), i.areaCode(), i.description()))
                .toList());
    var request = AiCapabilityRequest.of(CAPABILITY, CONTRACT_VERSION, payload);

    return switch (aiCapabilityInvoker.invoke(request, ResultPayload.class)) {
      case Failed<ResultPayload> failed -> new AssessmentUnavailable(failed.error());
      case Produced<ResultPayload> produced ->
          toVerdict(query, produced.result(), produced.correlationId());
    };
  }

  private RelevanceVerdict toVerdict(Query query, ResultPayload result, UUID correlationId) {
    if (result == null || result.reason() == null) {
      return contractViolation(correlationId, "relevance result was empty");
    }
    if (result.relevant() && result.reason().isBlank()) {
      return contractViolation(correlationId, "a relevant verdict must have a reason");
    }
    Set<String> knownAreaCodes =
        query.areas().stream().map(AreaContext::code).collect(Collectors.toUnmodifiableSet());
    Map<String, UUID> interestIdsByString =
        query.interests().stream()
            .collect(Collectors.toMap(i -> i.id().toString(), InterestContext::id));

    Set<String> matchedAreaCodes = Set.copyOf(nullToEmpty(result.matchedAreaCodes()));
    if (!knownAreaCodes.containsAll(matchedAreaCodes)) {
      return contractViolation(correlationId, "matched an area not in the supplied catalogue");
    }
    Set<UUID> matchedInterestIds;
    try {
      matchedInterestIds =
          nullToEmpty(result.matchedInterestIds()).stream()
              .map(
                  id -> {
                    UUID resolved = interestIdsByString.get(id);
                    if (resolved == null) {
                      throw new IllegalStateException("unknown interest id " + id);
                    }
                    return resolved;
                  })
              .collect(Collectors.toUnmodifiableSet());
    } catch (IllegalStateException unknownInterest) {
      return contractViolation(correlationId, "matched an interest that was not supplied");
    }
    if (result.relevant() && matchedAreaCodes.isEmpty()) {
      return contractViolation(correlationId, "a relevant verdict must match at least one area");
    }
    return new Assessed(result.relevant(), result.reason(), matchedAreaCodes, matchedInterestIds);
  }

  private static <T> List<T> nullToEmpty(List<T> value) {
    return value == null ? List.of() : value;
  }

  private static RelevanceVerdict contractViolation(UUID correlationId, String message) {
    return new AssessmentUnavailable(
        new AiError("AI_CONTRACT_VIOLATION", "contract", false, message, correlationId, Map.of()));
  }

  // --- wire payloads (mirror agents/app/capabilities/relevance.py) ---

  record RequestPayload(ItemText item, List<Area> areas, List<Interest> interests) {}

  record ItemText(String text) {}

  record Area(String code, String name) {}

  record Interest(String id, String areaCode, String description) {}

  record ResultPayload(
      boolean relevant,
      String reason,
      List<String> matchedAreaCodes,
      List<String> matchedInterestIds) {}
}
