package org.signalengine.infrastructure.persistence.summary;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC mapping for the {@code summary} table (migration V10). {@code signalId} is a
 * plain id reference — a summary is its own aggregate root
 * (docs/adr/0002-persistence-aggregate-boundaries.md).
 */
@Table("summary")
record SummaryRow(
    @Id UUID id,
    UUID signalId,
    String summaryText,
    String groundingNotes,
    @CreatedDate Instant createdAt,
    @LastModifiedDate Instant updatedAt) {}
