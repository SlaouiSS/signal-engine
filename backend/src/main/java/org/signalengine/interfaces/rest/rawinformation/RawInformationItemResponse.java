package org.signalengine.interfaces.rest.rawinformation;

import java.time.Instant;
import java.util.UUID;
import org.signalengine.domain.RawInformationItem;

/**
 * API representation of one collected raw information item (docs/05-data-model.md Section 8),
 * including its provenance, content, and processing state. Used for the provenance drill-down from
 * signal review.
 */
public record RawInformationItemResponse(
    UUID id,
    UUID sourceId,
    String sourceProvidedId,
    String contentHash,
    String originalUrl,
    String rawContent,
    String normalizedContent,
    String language,
    Instant publishedAt,
    Instant collectedAt,
    ProcessingStateResponse processingState,
    UUID relevantInformationId,
    Instant createdAt,
    Instant updatedAt) {

  public static RawInformationItemResponse from(RawInformationItem item) {
    return new RawInformationItemResponse(
        item.id(),
        item.sourceId(),
        item.sourceProvidedId(),
        item.contentHash(),
        item.originalUrl(),
        item.rawContent(),
        item.normalizedContent(),
        item.language(),
        item.publishedAt(),
        item.collectedAt(),
        ProcessingStateResponse.from(item.processingState()),
        item.relevantInformationId(),
        item.createdAt(),
        item.updatedAt());
  }
}
