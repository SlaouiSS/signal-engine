package org.signalengine.application.dedup;

import java.util.List;
import java.util.UUID;

/**
 * Input port: place a normalised {@link org.signalengine.domain.RawInformationItem} into a {@link
 * org.signalengine.domain.RelevantInformation} record — associating it with an existing one it
 * corroborates, or creating a new one (docs/11-roadmap.md Phase 5;
 * docs/adr/0007-semantic-deduplication-relevant-information.md).
 *
 * <p>Callable explicitly so a future scheduler or the relevance-assessment step (Task 7) can drive
 * it. Idempotent: an item that has already been grouped is skipped; running twice does not create a
 * second Relevant Information record or a duplicate association.
 */
public interface GroupIntoRelevantInformationUseCase {

  GroupingReport groupRawInformationItem(UUID rawInformationItemId);

  /** Process a bounded batch of items still awaiting near-duplicate assessment. */
  List<GroupingReport> groupNormalizedRawInformationItems();
}
