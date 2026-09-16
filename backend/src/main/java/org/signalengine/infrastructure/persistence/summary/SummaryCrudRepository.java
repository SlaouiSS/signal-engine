package org.signalengine.infrastructure.persistence.summary;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@link SummaryRow}. Infrastructure-only. */
interface SummaryCrudRepository extends ListCrudRepository<SummaryRow, UUID> {

  Optional<SummaryRow> findBySignalId(UUID signalId);
}
