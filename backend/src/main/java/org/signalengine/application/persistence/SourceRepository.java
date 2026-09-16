package org.signalengine.application.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.Source;

/**
 * Persistence contract for {@link Source} configuration (docs/05-data-model.md Section 7).
 *
 * <p>{@code save} both inserts a new source (id {@code null}) and updates an existing one. Removal
 * semantics are an open question (Q3), so no {@code delete} is exposed yet.
 */
public interface SourceRepository {

  Source save(Source source);

  Optional<Source> findById(UUID id);

  List<Source> findAll();
}
