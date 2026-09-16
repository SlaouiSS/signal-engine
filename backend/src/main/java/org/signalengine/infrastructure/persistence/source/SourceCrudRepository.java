package org.signalengine.infrastructure.persistence.source;

import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@link SourceRow}. Infrastructure-only. */
interface SourceCrudRepository extends ListCrudRepository<SourceRow, UUID> {}
