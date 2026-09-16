package org.signalengine.interfaces.rest.source;

import java.time.Instant;
import java.util.UUID;
import org.signalengine.domain.Source;

/** API representation of a configured source (docs/05-data-model.md Section 7). */
public record SourceResponse(
    UUID id,
    String type,
    String name,
    String reference,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {

  public static SourceResponse from(Source source) {
    return new SourceResponse(
        source.id(),
        source.type(),
        source.name(),
        source.reference(),
        source.enabled(),
        source.createdAt(),
        source.updatedAt());
  }
}
