package org.signalengine.application.usecase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.Source;

/**
 * Configure the curated sources Signal Engine collects from (docs/02-functional-spec.md Section 4,
 * workflow W1).
 *
 * <p>Removing and replacing a source are not offered here: the effect on already-collected
 * information is an open question (Q3) and the repository has no delete. Duplicate-source detection
 * is also deferred ("the same source" is Q2).
 */
public interface ManageSourcesUseCase {

  /** Registers a new, enabled source. Rejects a blank type, name, or reference. */
  Source registerSource(SourceConfiguration configuration);

  List<Source> listConfiguredSources();

  Optional<Source> findConfiguredSource(UUID sourceId);

  /** Edits a source's configuration. Empty if no such source. Rejects blank fields. */
  Optional<Source> updateSourceConfiguration(UUID sourceId, SourceConfiguration configuration);

  /** Stops future collection from the source; already-collected information is retained (R7). */
  Optional<Source> disableSource(UUID sourceId);

  Optional<Source> enableSource(UUID sourceId);
}
