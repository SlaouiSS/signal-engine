package org.signalengine.application.usecase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.Interest;

/**
 * Configure the interests that sharpen relevance within an area of interest
 * (docs/02-functional-spec.md Section 5, workflow W2).
 *
 * <p>Removing an interest is not offered (the repository has no delete). Disabling a whole area is
 * out of scope (Q6).
 */
public interface ManageInterestsUseCase {

  /**
   * Adds a new, enabled interest under an existing area of interest. Rejects a blank description
   * (W2, "empty interest: rejected") or an unknown area code.
   */
  Interest addInterest(String areaOfInterestCode, String description);

  List<Interest> listInterests();

  Optional<Interest> findInterest(UUID interestId);

  /** Edits an interest's natural-language description. Empty if no such interest. */
  Optional<Interest> updateInterestDescription(UUID interestId, String description);

  Optional<Interest> disableInterest(UUID interestId);

  Optional<Interest> enableInterest(UUID interestId);
}
