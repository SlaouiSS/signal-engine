package org.signalengine.interfaces.rest.interest;

import java.time.Instant;
import java.util.UUID;
import org.signalengine.domain.Interest;

/** API representation of a configured interest (docs/05-data-model.md Section 6). */
public record InterestResponse(
    UUID id,
    String areaOfInterestCode,
    String description,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {

  public static InterestResponse from(Interest interest) {
    return new InterestResponse(
        interest.id(),
        interest.areaOfInterestCode(),
        interest.description(),
        interest.enabled(),
        interest.createdAt(),
        interest.updatedAt());
  }
}
