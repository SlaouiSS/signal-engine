package org.signalengine.infrastructure.persistence.activity;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC mapping for the {@code activity_record} table (migration V9). Append-only.
 * {@code occurredAt} is the event time supplied by the caller, not an audit timestamp.
 */
@Table("activity_record")
record ActivityRecordRow(
    @Id UUID id,
    Instant occurredAt,
    String category,
    String outcome,
    String message,
    UUID sourceId,
    UUID rawInformationItemId) {}
