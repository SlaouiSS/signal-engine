package org.signalengine.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A user-defined refinement of relevance within one area of interest (docs/05-data-model.md Section
 * 6). It belongs to exactly one {@link AreaOfInterest} (referenced by {@code code}).
 *
 * <p>The exact form of {@code description} is an open question (Q6); it is carried as free text.
 * The {@code with*} methods return a copy with one aspect changed and hold no rules.
 */
public record Interest(
    UUID id,
    String areaOfInterestCode,
    String description,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {

  public Interest withEnabled(boolean newEnabled) {
    return new Interest(id, areaOfInterestCode, description, newEnabled, createdAt, updatedAt);
  }

  public Interest withDescription(String newDescription) {
    return new Interest(id, areaOfInterestCode, newDescription, enabled, createdAt, updatedAt);
  }
}
