package org.signalengine.infrastructure.persistence.signal;

import java.time.Instant;
import java.util.UUID;
import org.signalengine.domain.SignalState;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC mapping for the {@code signal} table (migration V7). {@link SignalState} maps to
 * the {@code state} text column by enum name, matching the migration's {@code CHECK} values.
 */
@Table("signal")
record SignalRow(
    @Id UUID id,
    UUID relevantInformationId,
    SignalState state,
    @CreatedDate Instant createdAt,
    @LastModifiedDate Instant updatedAt) {}
