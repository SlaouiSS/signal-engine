package org.signalengine.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The user's judgement on a signal (docs/05-data-model.md Section 12). Attributed to exactly one
 * {@link Signal}. Append-only: recorded once with a timestamp, never updated.
 */
public record Feedback(UUID id, UUID signalId, FeedbackVerdict verdict, Instant createdAt) {}
