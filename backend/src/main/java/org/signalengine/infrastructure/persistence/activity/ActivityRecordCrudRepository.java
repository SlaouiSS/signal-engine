package org.signalengine.infrastructure.persistence.activity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@link ActivityRecordRow}. Infrastructure-only. */
interface ActivityRecordCrudRepository extends ListCrudRepository<ActivityRecordRow, UUID> {

  @Query("SELECT * FROM activity_record ORDER BY occurred_at DESC LIMIT :maxResults")
  List<ActivityRecordRow> findMostRecent(int maxResults);
}
