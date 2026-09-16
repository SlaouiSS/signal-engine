package org.signalengine.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The concise, source-grounded account of a {@link Signal} (docs/05-data-model.md Section 11;
 * docs/02-functional-spec.md Section 8). Exactly one Summary per Signal, generated after the Signal
 * is created.
 *
 * <p>{@code summaryText} is drawn only from the source content supplied to the summarisation
 * capability; {@code groundingNotes} records what it is based on and flags any interpretation, so
 * generated interpretation stays distinguishable from source facts (R4). A Signal without a Summary
 * row is "summary pending"; a failed generation is recorded in activity and retried — it never
 * fabricates a Summary.
 *
 * <p>No exact length or format is fixed — that is an open product question (Q14); any length
 * guidance in the prompt is provisional.
 */
public record Summary(
    UUID id,
    UUID signalId,
    String summaryText,
    String groundingNotes,
    Instant createdAt,
    Instant updatedAt) {}
