package org.signalengine.infrastructure.persistence.source;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.domain.Source;
import org.springframework.stereotype.Repository;

/** Spring Data JDBC implementation of the {@link SourceRepository} port. */
@Repository
class SourceRepositoryAdapter implements SourceRepository {

  private final SourceCrudRepository sourceCrudRepository;

  SourceRepositoryAdapter(SourceCrudRepository sourceCrudRepository) {
    this.sourceCrudRepository = sourceCrudRepository;
  }

  @Override
  public Source save(Source source) {
    return toDomain(sourceCrudRepository.save(toRow(source)));
  }

  @Override
  public Optional<Source> findById(UUID id) {
    return sourceCrudRepository.findById(id).map(SourceRepositoryAdapter::toDomain);
  }

  @Override
  public List<Source> findAll() {
    return sourceCrudRepository.findAll().stream().map(SourceRepositoryAdapter::toDomain).toList();
  }

  private static SourceRow toRow(Source source) {
    return new SourceRow(
        source.id(),
        source.type(),
        source.name(),
        source.reference(),
        source.enabled(),
        source.createdAt(),
        source.updatedAt());
  }

  private static Source toDomain(SourceRow row) {
    return new Source(
        row.id(),
        row.type(),
        row.name(),
        row.reference(),
        row.enabled(),
        row.createdAt(),
        row.updatedAt());
  }
}
