package org.signalengine.infrastructure.persistence.relevantinformation;

import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Owned child of {@link RelevantInformationRow}: one row of {@code relevant_information_interest}
 * (migration V6) — a matched interest this relevant-information record relates to.
 */
@Table("relevant_information_interest")
record MatchedInterestRow(UUID interestId) {}
