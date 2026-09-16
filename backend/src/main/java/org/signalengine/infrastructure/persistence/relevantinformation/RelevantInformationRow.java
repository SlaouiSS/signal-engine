package org.signalengine.infrastructure.persistence.relevantinformation;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC mapping for the {@code relevant_information} table (migration V6).
 *
 * <p>The matched-area and matched-interest link rows are owned children of this aggregate
 * (docs/adr/0002-persistence-aggregate-boundaries.md). On save, Spring Data JDBC replaces the child
 * rows for this parent; on load, it reads them by the {@code relevant_information_id}
 * back-reference.
 */
@Table("relevant_information")
record RelevantInformationRow(
    @Id UUID id,
    String reason,
    @MappedCollection(idColumn = "relevant_information_id") Set<MatchedAreaRow> areas,
    @MappedCollection(idColumn = "relevant_information_id") Set<MatchedInterestRow> interests,
    @CreatedDate Instant createdAt,
    @LastModifiedDate Instant updatedAt) {}
