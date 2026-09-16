package org.signalengine.application.usecase;

import java.util.List;
import java.util.Optional;
import org.signalengine.domain.AreaOfInterest;

/**
 * View the six fixed areas of interest (docs/02-functional-spec.md Section 5.2, "View the six
 * areas"; docs/05-data-model.md Section 5).
 *
 * <p>Read-only: the set of areas is fixed product configuration seeded by migration, so there are
 * no add / edit / remove operations. The task groups this under "manage areas of interest", but no
 * management operation is supported by the model.
 */
public interface ReviewAreasOfInterestUseCase {

  List<AreaOfInterest> listAreasOfInterest();

  Optional<AreaOfInterest> findAreaOfInterest(String areaOfInterestCode);
}
