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
                 PARTITION BY source_id ORDER BY collected_at ASC, id ASC
               ) AS source_rank
        FROM raw_information_item
        WHERE processing_state = :processingState
      ) ranked_by_source
      ORDER BY source_rank ASC, collected_at ASC, id ASC
      LIMIT :limit
      """)
  List<RawInformationItemRow> findByProcessingState(String processingState, int limit);

  @Query(
      """
      SELECT * FROM raw_information_item
      WHERE processing_state IN (:processingStates)
      ORDER BY collected_at ASC
      LIMIT :limit
      """)
  List<RawInformationItemRow> findByProcessingStateIn(List<String> processingStates, int limit);

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
}
