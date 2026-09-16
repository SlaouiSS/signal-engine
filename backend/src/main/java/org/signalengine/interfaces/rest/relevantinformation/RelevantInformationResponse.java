package org.signalengine.interfaces.rest.relevantinformation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.signalengine.domain.RelevantInformation;

/**
 * API representation of a relevant-information record (docs/05-data-model.md Section 9), with the
 * areas and interests it matched. The matched collections are returned as sorted lists for a stable
 * contract.
 */
public record RelevantInformationResponse(
    UUID id,
    String reason,
    List<String> matchedAreaCodes,
    List<UUID> matchedInterestIds,
    Instant createdAt,
    Instant updatedAt) {

  public static RelevantInformationResponse from(RelevantInformation relevantInformation) {
    return new RelevantInformationResponse(
        relevantInformation.id(),
        relevantInformation.reason(),
        relevantInformation.matchedAreaCodes().stream().sorted().toList(),
        relevantInformation.matchedInterestIds().stream()
            .sorted(Comparator.naturalOrder())
            .toList(),
        relevantInformation.createdAt(),
        relevantInformation.updatedAt());
  }
}
