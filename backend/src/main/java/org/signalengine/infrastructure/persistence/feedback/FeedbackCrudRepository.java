package org.signalengine.infrastructure.persistence.feedback;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@link FeedbackRow}. Infrastructure-only. */
interface FeedbackCrudRepository extends ListCrudRepository<FeedbackRow, UUID> {

  List<FeedbackRow> findBySignalId(UUID signalId);
}
