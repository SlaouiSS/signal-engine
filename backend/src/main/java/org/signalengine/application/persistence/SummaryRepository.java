package org.signalengine.application.persistence;

import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.Summary;

/**
 * Persistence contract for {@link Summary} (docs/05-data-model.md Section 11). Exactly one summary
 * per signal, so {@link #findBySignalId(UUID)} returns at most one; the {@code signal_id} unique
 * constraint is the final guard against a duplicate summary.
 */
public interface SummaryRepository {

  Summary save(Summary summary);

  Optional<Summary> findById(UUID id);

  Optional<Summary> findBySignalId(UUID signalId);
}
