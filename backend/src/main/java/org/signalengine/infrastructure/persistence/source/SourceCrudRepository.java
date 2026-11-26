package org.signalengine.infrastructure.persistence.source;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@link SourceRow}. Infrastructure-only. */
interface SourceCrudRepository extends ListCrudRepository<SourceRow, UUID> {

  @Query("SELECT * FROM source ORDER BY last_collection_attempted_at ASC NULLS FIRST, id ASC")
  List<SourceRow> findAllLeastRecentlyCollectedFirst();

  /** Touches only the collection bookkeeping columns, which {@link SourceRow} does not map. */
  @Modifying
  @Query(
      """
      UPDATE source
      SET consecutive_collection_failures =
              CASE WHEN :succeeded THEN 0 ELSE consecutive_collection_failures + 1 END,
          last_collection_attempted_at = :attemptedAt
      WHERE id = :id
      """)
  void recordCollectionAttempt(UUID id, boolean succeeded, Instant attemptedAt);
}
