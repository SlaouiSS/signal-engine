package org.signalengine.application.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.Interest;

/** Persistence contract for {@link Interest} configuration (docs/05-data-model.md Section 6). */
public interface InterestRepository {

  Interest save(Interest interest);

  Optional<Interest> findById(UUID id);

  List<Interest> findAll();
}
