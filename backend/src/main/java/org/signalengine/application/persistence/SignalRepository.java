package org.signalengine.application.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.Signal;

/**
 * Persistence contract for {@link Signal} (docs/05-data-model.md Section 10). A signal maps to
 * exactly one relevant-information record, so {@link #findByRelevantInformationId(UUID)} returns at
 * most one.
 */
public interface SignalRepository {

  Signal save(Signal signal);

  Optional<Signal> findById(UUID id);

  Optional<Signal> findByRelevantInformationId(UUID relevantInformationId);

  /**
   * The most recently created signals, newest first (docs/02-functional-spec.md Section 9.4,
   * workflow W6). Ordered by {@code createdAt} — when the signal was produced, not last modified —
   * which is not an open question; only the {@code Q16} default-ordering-of-the-review-list choice
   * this resolves is provisional.
   */
  List<Signal> findMostRecent(int maxResults);
}
