package org.signalengine.infrastructure.persistence.areaofinterest;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Spring Data JDBC mapping for the {@code area_of_interest} table (migration V2, natural key). */
@Table("area_of_interest")
record AreaOfInterestRow(@Id String code, String name) {}
