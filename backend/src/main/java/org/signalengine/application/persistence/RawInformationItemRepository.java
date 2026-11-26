package org.signalengine.application.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;

/**
 * Persistence contract for {@link RawInformationItem} (docs/05-data-model.md Section 8).
 *
 * <p>{@link #findByRelevantInformationId(UUID)} realises "the grouped Relevant Information retains
 * references to every contributing Raw Information Item" (docs/05-data-model.md Section 4) without
 * making raw items a child collection of the relevant-information aggregate.
 */
public interface RawInformationItemRepository {

  RawInformationItem save(RawInformationItem item);

  /**
   * Persists {@code item}, or reports that an item with the same deterministic collection identity
   * (source, source-provided id, content hash — {@code NULLS NOT DISTINCT}) already exists.
   *
   * <p>The database's {@code raw_information_item_identity_key} unique constraint is the <em>final
   * authority</em> for exact-collection identity (docs/05-data-model.md Section 17–18;
   * docs/03-technical-spec.md Section 10.4). A caller may check {@link #findByIdentity} first as a
   * fast path, but two concurrent collections of the same item can both pass that check; the one
   * that loses the insert race is recognised here as an exact duplicate — the same idempotent
   * outcome as a sequential re-collection (docs/08-ingestion.md Section 15), not a persistence
   * failure. Any other integrity violation propagates.
   *
   * @return {@code true} if this call inserted a new item; {@code false} if an equal-identity item
   *     already existed — whether it was present beforehand or created by a concurrent collection
   */
  boolean saveIfNew(RawInformationItem item);

  Optional<RawInformationItem> findById(UUID id);

  List<RawInformationItem> findByRelevantInformationId(UUID relevantInformationId);

  /**
   * Looks up an item by its deterministic collection identity — source, source-provided id (which
   * may be {@code null}), and content hash (docs/03-technical-spec.md Section 10.4;
   * docs/05-data-model.md Section 17). Used by ingestion to skip exact duplicates.
   */
  Optional<RawInformationItem> findByIdentity(
      UUID sourceId, String sourceProvidedId, String contentHash);

  /**
   * A bounded page of items in a given processing state whose next attempt is due at {@code dueAt}
   * — the pending work a pipeline stage picks up (docs/03-technical-spec.md Section 6.3, 10.3).
   * Fair across sources: each source's own items are returned in order of their last attempt,
   * oldest first (an item that was attempted goes behind the ones not attempted yet, see {@link
   * #recordRetryableAttempt}), but the page interleaves round-robin across every source that has
   * pending items, so one source with a very large backlog cannot monopolize the page and starve
   * the others.
   */
  List<RawInformationItem> findDueByProcessingState(
      String processingState, java.time.Instant dueAt, int limit);

  /**
   * Counts one failed attempt of the item's current stage and returns the new count for {@code
   * kind}; empty if the item is no longer in {@code expectedState}. Every transition resets the
   * counts.
   */
  java.util.OptionalInt countFailedAttempt(
      UUID rawInformationItemId, String expectedState, FailedAttemptKind kind);

  /**
   * Records an attempt and makes the item wait until {@code nextAttemptAt}. Returns {@code false}
   * if the item is no longer in {@code expectedState}.
   */
  boolean postponeNextAttempt(
      UUID rawInformationItemId,
      String expectedState,
      java.time.Instant attemptedAt,
      java.time.Instant nextAttemptAt);

  /** Every item that was set aside by a failure that may not repeat (failed and retryable). */
  List<RawInformationItem> findRetryableFailures();

  /**
   * Returns a retryable failure of {@code failedStage} to {@code resumeState}, with its failure
   * details and counts cleared. Returns {@code false} if the item is no longer such a failure, so
   * repeating the call changes nothing and a permanent failure is never requeued.
   */
  boolean requeueRetryableFailure(
      UUID rawInformationItemId,
      String failedStage,
      String resumeState,
      java.time.Instant requeuedAt);

  /**
   * The {@code limit} most recently collected items that are already associated with a Relevant
   * Information record — the bounded candidate pool for semantic near-duplicate assessment
   * (docs/adr/0007-semantic-deduplication-relevant-information.md). Deterministic, so it never
   * grows into an O(n^2) all-history comparison.
   */
  List<RawInformationItem> findMostRecentlyGrouped(int limit);

  /**
   * Atomically moves an item out of {@code expectedState}: sets its new processing state and its
   * Relevant Information reference. Returns {@code false} if the item was no longer in {@code
   * expectedState} — another run of the same stage already handled it. This conditional update is
   * the idempotency/concurrency guard for near-duplicate grouping (Task 6B) and the
   * relevance/importance/summary pipeline (Task 7); no distributed lock is used
   * (docs/05-data-model.md Section 14; docs/08-ingestion.md Section 15).
   *
   * @param relevantInformationId the record to associate — for a Task 6B "distinct" transition this
   *     is the new record; for every Task 7 transition (including a failure) it is the item's
   *     existing record, so the link is preserved; {@code null} only for a Task 6B failure of an
   *     un-grouped item
   */
  boolean transitionFromState(
      UUID rawInformationItemId,
      String expectedState,
      ProcessingState newState,
      UUID relevantInformationId);

  /**
   * Records that a stage attempted an item and failed with a <em>retryable</em> error, leaving the
   * item in {@code expectedState}: only its last-attempt time moves, so a page of pending work
   * (oldest attempt first) puts it behind the items not attempted yet. Without this, items that
   * keep failing transiently would stay at the head of every page and starve the rest of the queue
   * (docs/05-data-model.md Section 14: one failed item must not block unrelated items). Returns
   * {@code false} if the item is no longer in {@code expectedState}.
   */
  boolean recordRetryableAttempt(
      UUID rawInformationItemId, String expectedState, java.time.Instant attemptedAt);
}
