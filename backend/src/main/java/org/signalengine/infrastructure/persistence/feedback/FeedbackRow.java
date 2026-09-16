package org.signalengine.infrastructure.persistence.feedback;

import java.time.Instant;
import java.util.UUID;
import org.signalengine.domain.FeedbackVerdict;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC mapping for the {@code feedback} table (migration V8). Append-only, so there is
 * no last-modified field. {@link FeedbackVerdict} maps to the {@code verdict} text column by enum
 * name, matching the migration's {@code CHECK} values.
 */
@Table("feedback")
record FeedbackRow(
    @Id UUID id, UUID signalId, FeedbackVerdict verdict, @CreatedDate Instant createdAt) {}
