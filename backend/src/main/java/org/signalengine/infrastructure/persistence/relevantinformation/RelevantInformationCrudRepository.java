package org.signalengine.infrastructure.persistence.relevantinformation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@link RelevantInformationRow}. Infrastructure-only. */
interface RelevantInformationCrudRepository
    extends ListCrudRepository<RelevantInformationRow, UUID> {

  @Query("SELECT * FROM relevant_information ORDER BY created_at DESC LIMIT :maxResults")
  List<RelevantInformationRow> findMostRecent(int maxResults);
}
