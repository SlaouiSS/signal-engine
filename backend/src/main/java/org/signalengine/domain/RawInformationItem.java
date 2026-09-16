package org.signalengine.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One item as collected from a source, before assessment (docs/05-data-model.md Section 8).
 *
 * <p>It belongs to exactly one {@link Source}. {@code relevantInformationId} is {@code null} until
 * relevance assessment groups the item into a {@link RelevantInformation} record (many raw items to
 * at most one relevant-information record). {@code sourceProvidedId}, {@code originalUrl}, {@code
 * rawContent}, {@code normalizedContent}, {@code language}, and {@code publishedAt} may be {@code
 * null}.
 */
public record RawInformationItem(
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
    ProcessingState processingState,
    UUID relevantInformationId,
    Instant createdAt,
    Instant updatedAt) {}
