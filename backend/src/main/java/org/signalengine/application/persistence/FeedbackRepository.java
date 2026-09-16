package org.signalengine.application.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.Feedback;

/**
 * Persistence contract for {@link Feedback} (docs/05-data-model.md Section 12). Append-only: only
 * {@code save} of a new record, plus reads. No update or delete is exposed.
 */
public interface FeedbackRepository {

  Feedback save(Feedback feedback);

  Optional<Feedback> findById(UUID id);

  List<Feedback> findBySignalId(UUID signalId);
}
