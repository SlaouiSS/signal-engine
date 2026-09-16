package org.signalengine.domain;

import java.time.Instant;

/**
 * The explicit, persisted processing position of a {@link RawInformationItem}
 * (docs/05-data-model.md Section 14).
 *
 * <p>{@code state} is free text: the state vocabulary is explicitly not finalised
 * (docs/05-data-model.md Section 14; docs/03-technical-spec.md Section 10.3). The failure fields
 * are the structure the documentation does require — which stage failed, why, and whether it is
 * retryable. All failure fields are {@code null} unless the item is in a failed state.
 *
 * <p>The named states below are all drawn from the illustrative sequence in
 * docs/03-technical-spec.md Section 10.3 ({@code received -> normalized -> (duplicate |
 * deduplicated) -> classified -> relevance-assessed -> (not-relevant | relevant) ->
 * importance-assessed -> (signal-created | no-signal) -> summarized -> complete}); the final
 * vocabulary is still open. Meaning here (the position is tracked on the RI's anchor item — the
 * contributing item that is not a {@code duplicate}):
 *
 * <ul>
 *   <li>{@code normalized} — past exact deduplication; awaiting semantic near-duplicate assessment
 *       (ingestion, Task 5).
 *   <li>{@code duplicate} — a semantic near-duplicate of existing Relevant Information (Task 6B).
 *   <li>{@code deduplicated} — semantically distinct, anchors its own Relevant Information;
 *       awaiting relevance assessment (Task 6B).
 *   <li>{@code relevant} — relevance assessment found it relevant; awaiting importance assessment
 *       (Task 7).
 *   <li>{@code not_relevant} — relevance assessment found it not relevant; set aside as noise,
 *       terminal (Task 7).
 *   <li>{@code signal_created} — importance assessment found it important; a Signal exists; the
 *       summary is pending or being retried (Task 7).
 *   <li>{@code no_signal} — importance assessment found it not important enough; kept as Relevant
 *       Information, no Signal, terminal (Task 7).
 *   <li>{@code summarized} — the Signal has a source-grounded Summary; terminal for Task 7 (Task
 *       7).
 *   <li>{@code failed} — a stage failed.
 * </ul>
 */
public record ProcessingState(
    String state,
    String failedStage,
    String failureReason,
    Boolean failureRetryable,
    Instant updatedAt) {

  public static final String NORMALIZED = "normalized";
  public static final String DUPLICATE = "duplicate";
  public static final String DEDUPLICATED = "deduplicated";
  public static final String RELEVANT = "relevant";
  public static final String NOT_RELEVANT = "not_relevant";
  public static final String SIGNAL_CREATED = "signal_created";
  public static final String NO_SIGNAL = "no_signal";
  public static final String SUMMARIZED = "summarized";
  public static final String FAILED = "failed";

  public static ProcessingState normalized(Instant at) {
    return plain(NORMALIZED, at);
  }

  /** The item corroborates existing Relevant Information as a near-duplicate. */
  public static ProcessingState duplicate(Instant at) {
    return plain(DUPLICATE, at);
  }

  /**
   * Semantically distinct — anchors its own Relevant Information; awaiting relevance assessment.
   */
  public static ProcessingState deduplicated(Instant at) {
    return plain(DEDUPLICATED, at);
  }

  /** Found relevant; awaiting importance assessment. */
  public static ProcessingState relevant(Instant at) {
    return plain(RELEVANT, at);
  }

  /** Found not relevant; set aside as noise (terminal). */
  public static ProcessingState notRelevant(Instant at) {
    return plain(NOT_RELEVANT, at);
  }

  /** Important enough; a Signal exists, its Summary is pending. */
  public static ProcessingState signalCreated(Instant at) {
    return plain(SIGNAL_CREATED, at);
  }

  /** Relevant but not important enough for a Signal (terminal). */
  public static ProcessingState noSignal(Instant at) {
    return plain(NO_SIGNAL, at);
  }

  /** The Signal has a source-grounded Summary (terminal for Task 7). */
  public static ProcessingState summarized(Instant at) {
    return plain(SUMMARIZED, at);
  }

  /** A stage failed; carries which stage, why, and whether a retry may succeed. */
  public static ProcessingState failed(String stage, String reason, boolean retryable, Instant at) {
    return new ProcessingState(FAILED, stage, reason, retryable, at);
  }

  private static ProcessingState plain(String state, Instant at) {
    return new ProcessingState(state, null, null, null, at);
  }
}
