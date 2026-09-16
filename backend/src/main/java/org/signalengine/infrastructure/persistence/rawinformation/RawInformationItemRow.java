package org.signalengine.infrastructure.persistence.rawinformation;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC mapping for the {@code raw_information_item} table (migrations V5, V6).
 *
 * <p>The processing-state columns are flat here (matching the table); the adapter groups them into
 * the domain's {@code ProcessingState} value object
 * (docs/adr/0001-persistence-schema-foundation.md: processing state is columns on the item, not a
 * separate table). {@code relevantInformationId} is a plain id reference — raw items are their own
 * aggregate root (docs/adr/0002-persistence-aggregate-boundaries.md).
 */
@Table("raw_information_item")
record RawInformationItemRow(
    @Id UUID id,
    UUID sourceId,
    String sourceProvidedId,
    String contentHash,
    String originalUrl,
    String rawContent,
    String normalizedContent,
    String language,
    Instant publishedAt,
    Instant collectedAt,
    String processingState,
    String failedStage,
    String failureReason,
    Boolean failureRetryable,
    Instant processingUpdatedAt,
    UUID relevantInformationId,
    @CreatedDate Instant createdAt,
    @LastModifiedDate Instant updatedAt) {}
