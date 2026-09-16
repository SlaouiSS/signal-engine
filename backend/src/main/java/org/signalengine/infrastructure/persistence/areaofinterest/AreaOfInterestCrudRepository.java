package org.signalengine.infrastructure.persistence.areaofinterest;

import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@link AreaOfInterestRow}. Read-only in practice. */
interface AreaOfInterestCrudRepository extends ListCrudRepository<AreaOfInterestRow, String> {}
