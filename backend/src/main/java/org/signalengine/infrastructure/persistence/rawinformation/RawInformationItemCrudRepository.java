package org.signalengine.infrastructure.persistence.rawinformation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@link RawInformationItemRow}. Infrastructure-only. */
interface RawInformationItemCrudRepository extends ListCrudRepository<RawInformationItemRow, UUID> {

  List<RawInformationItemRow> findByRelevantInformationId(UUID relevantInformationId);

  /**
   * {@code IS NOT DISTINCT FROM} matches the migration V5 unique key, which is {@code NULLS NOT
   * DISTINCT}: a {@code null} source-provided id matches a stored {@code null}.
   */
  @Query(
      """
      SELECT * FROM raw_information_item
      WHERE source_id = :sourceId
        AND source_provided_id IS NOT DISTINCT FROM :sourceProvidedId
        AND content_hash = :contentHash
      """)
  Optional<RawInformationItemRow> findByIdentity(
      UUID sourceId, String sourceProvidedId, String contentHash);

  /**
   * A page of items in {@code processingState}, fair across sources: ranked first by each item's
   * position within its own source's oldest-first queue ({@code source_rank}, via {@code
   * ROW_NUMBER() OVER (PARTITION BY source_id ORDER BY collected_at, id)}), then by {@code
   * collected_at}/{@code id}. Taking the lowest {@code source_rank} first means every source with
   * at least one pending item contributes its oldest item before any source contributes its second,
   * so a source with a very large backlog cannot fill the whole batch and starve every other source
   * — round-robin across sources, FIFO within each one. {@code id} is the final, always-distinct
   * tiebreaker, so the ordering (and therefore the page) is fully deterministic.
   */
  @Query(
      """
      SELECT id, source_id, source_provided_id, content_hash, original_url, raw_content,
             normalized_content, language, published_at, collected_at, processing_state,
             failed_stage, failure_reason, failure_retryable, processing_updated_at,
             relevant_information_id, created_at, updated_at
      FROM (
        SELECT *,
               ROW_NUMBER() OVER (
                 PARTITION BY source_id ORDER BY processing_updated_at ASC, id ASC
               ) AS source_rank
        FROM raw_information_item
        WHERE processing_state = :processingState
          AND (next_attempt_at IS NULL OR next_attempt_at <= :dueAt)
      ) ranked_by_source
      ORDER BY source_rank ASC, processing_updated_at ASC, id ASC
      LIMIT :limit
      """)
  List<RawInformationItemRow> findDueByProcessingState(
      String processingState, Instant dueAt, int limit);

  @Query(
      """
      UPDATE raw_information_item
      SET transient_failures = transient_failures + 1
      WHERE id = :id AND processing_state = :expectedState
      RETURNING transient_failures
      """)
  Optional<Integer> countTransientFailure(UUID id, String expectedState);

  @Query(
      """
      UPDATE raw_information_item
      SET unexpected_failures = unexpected_failures + 1
      WHERE id = :id AND processing_state = :expectedState
      RETURNING unexpected_failures
      """)
  Optional<Integer> countUnexpectedFailure(UUID id, String expectedState);

  @Modifying
  @Query(
      """
      UPDATE raw_information_item
      SET processing_updated_at = :attemptedAt,
          next_attempt_at       = :nextAttemptAt,
          updated_at            = :attemptedAt
      WHERE id = :id AND processing_state = :expectedState
      """)
  boolean postponeNextAttempt(
      UUID id, String expectedState, Instant attemptedAt, Instant nextAttemptAt);

  @Query(
      """
      SELECT * FROM raw_information_item
      WHERE processing_state = 'failed' AND failure_retryable
      ORDER BY processing_updated_at ASC, id ASC
      """)
  List<RawInformationItemRow> findRetryableFailures();

  @Modifying
  @Query(
      """
      UPDATE raw_information_item
      SET processing_state      = :resumeState,
          failed_stage          = NULL,
          failure_reason        = NULL,
          failure_retryable     = NULL,
          transient_failures    = 0,
          unexpected_failures   = 0,
          next_attempt_at       = NULL,
          processing_updated_at = :requeuedAt,
          updated_at            = :requeuedAt
      WHERE id = :id
        AND processing_state = 'failed'
        AND failure_retryable
        AND failed_stage = :failedStage
      """)
  boolean requeueRetryableFailure(
      UUID id, String failedStage, String resumeState, Instant requeuedAt);

  @Query(
      """
      SELECT * FROM raw_information_item
      WHERE relevant_information_id IS NOT NULL
      ORDER BY collected_at DESC
      LIMIT :limit
      """)
  List<RawInformationItemRow> findMostRecentlyGrouped(int limit);

  /**
   * Conditional transition: only updates the row while it is still in {@code expectedState}, so two
   * concurrent runs of the same stage cannot both act on it. Returns whether a row was updated.
   * {@code updated_at} is set explicitly because a modifying query does not trigger auditing.
   */
  @Modifying
  @Query(
      """
      UPDATE raw_information_item
      SET relevant_information_id = :relevantInformationId,
          processing_state       = :state,
          failed_stage           = :failedStage,
          failure_reason         = :failureReason,
          failure_retryable      = :failureRetryable,
          transient_failures     = 0,
          unexpected_failures    = 0,
          next_attempt_at        = NULL,
          processing_updated_at  = :updatedAt,
          updated_at             = :updatedAt
      WHERE id = :id AND processing_state = :expectedState
      """)
  boolean transitionFromState(
      UUID id,
      String expectedState,
      String state,
      String failedStage,
      String failureReason,
      Boolean failureRetryable,
      Instant updatedAt,
      UUID relevantInformationId);

  /** Moves only the last-attempt time of an item still in {@code expectedState}. */
  @Modifying
  @Query(
      """
      UPDATE raw_information_item
      SET processing_updated_at = :attemptedAt,
          updated_at            = :attemptedAt
      WHERE id = :id AND processing_state = :expectedState
      """)
  boolean recordRetryableAttempt(UUID id, String expectedState, Instant attemptedAt);
}
