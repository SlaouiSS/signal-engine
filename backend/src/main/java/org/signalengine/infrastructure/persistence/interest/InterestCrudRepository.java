package org.signalengine.infrastructure.persistence.interest;

import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@link InterestRow}. Infrastructure-only. */
interface InterestCrudRepository extends ListCrudRepository<InterestRow, UUID> {}
