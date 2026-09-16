package org.signalengine.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The grouped, retained representation of information supported by one or more {@link
 * RawInformationItem}s (docs/05-data-model.md Section 9).
 *
 * <p>Two-step lifecycle: created as an empty grouping container at near-duplicate assessment (Task
 * 6B), then enriched by relevance assessment (Task 7) with the user-visible {@code reason} and the
 * matched Area(s) of Interest and Interest(s). {@code matchedAreaCodes} and {@code
 * matchedInterestIds} may be empty; {@code reason} may be {@code null}. No relevance score is
 * modelled (Section 9 leaves it open).
 *
 * <p>The contributing {@link RawInformationItem}s are not held here; they reference this record by
 * id and are looked up through the raw-information-item repository.
 */
public record RelevantInformation(
    UUID id,
    String reason,
    Set<String> matchedAreaCodes,
    Set<UUID> matchedInterestIds,
    Instant createdAt,
    Instant updatedAt) {

  /**
   * A copy carrying the relevance-assessment result — the user-visible reason and the matched
   * areas/interests. Holds no rules; the decision to enrich is the application's.
   */
  public RelevantInformation withRelevance(
      String newReason, Set<String> newMatchedAreaCodes, Set<UUID> newMatchedInterestIds) {
    return new RelevantInformation(
        id,
        newReason,
        Set.copyOf(newMatchedAreaCodes),
        Set.copyOf(newMatchedInterestIds),
        createdAt,
        updatedAt);
  }
}
