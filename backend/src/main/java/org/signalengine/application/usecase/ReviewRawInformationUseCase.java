package org.signalengine.application.usecase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.RawInformationItem;

/**
 * Inspect the collected raw information behind a relevant-information record — the provenance
 * drill-down from signal review (docs/05-data-model.md Section 4, Section 8, Section 15).
 *
 * <p>There is no browse-all operation: raw information items are an internal pipeline artifact, not
 * a top-level user list, and the repository exposes only lookup by id and by relevant-information.
 */
public interface ReviewRawInformationUseCase {

  Optional<RawInformationItem> findRawInformationItem(UUID rawInformationItemId);

  /** The raw items that were grouped into the given relevant-information record. */
  List<RawInformationItem> listContributingRawInformationItems(UUID relevantInformationId);
}
