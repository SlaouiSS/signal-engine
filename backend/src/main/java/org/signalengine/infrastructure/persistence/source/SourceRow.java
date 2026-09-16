package org.signalengine.infrastructure.persistence.source;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Table;

/** Spring Data JDBC mapping for the {@code source} table (migration V3). */
@Table("source")
record SourceRow(
    @Id UUID id,
    String type,
    String name,
    String reference,
    boolean enabled,
    @CreatedDate Instant createdAt,
    @LastModifiedDate Instant updatedAt) {}
