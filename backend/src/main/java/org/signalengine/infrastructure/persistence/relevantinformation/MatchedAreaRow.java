package org.signalengine.infrastructure.persistence.relevantinformation;

import org.springframework.data.relational.core.mapping.Table;

/**
 * Owned child of {@link RelevantInformationRow}: one row of {@code relevant_information_area}
 * (migration V6) — an area of interest this relevant-information record relates to.
 */
@Table("relevant_information_area")
record MatchedAreaRow(String areaOfInterestCode) {}
