package org.signalengine.infrastructure.persistence.interest;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Table;

/** Spring Data JDBC mapping for the {@code interest} table (migration V4). */
@Table("interest")
record InterestRow(
    @Id UUID id,
    String areaOfInterestCode,
    String description,
    boolean enabled,
    @CreatedDate Instant createdAt,
    @LastModifiedDate Instant updatedAt) {}
