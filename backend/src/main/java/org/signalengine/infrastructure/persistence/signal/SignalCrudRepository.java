package org.signalengine.infrastructure.persistence.signal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@link SignalRow}. Infrastructure-only. */
interface SignalCrudRepository extends ListCrudRepository<SignalRow, UUID> {

  Optional<SignalRow> findByRelevantInformationId(UUID relevantInformationId);

  @Query("SELECT * FROM signal ORDER BY created_at DESC LIMIT :maxResults")
  List<SignalRow> findMostRecent(int maxResults);
}
