package org.signalengine.application.persistence;

import java.util.List;
import java.util.Optional;
import org.signalengine.domain.AreaOfInterest;

/**
 * Read access to the six fixed areas of interest (docs/05-data-model.md Section 5).
 *
 * <p>Read-only by design: the set is fixed and seeded by migration, so no {@code save} is exposed.
 */
public interface AreaOfInterestRepository {

  List<AreaOfInterest> findAll();

  Optional<AreaOfInterest> findByCode(String code);
}
