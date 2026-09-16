package org.signalengine.infrastructure.persistence.signal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.domain.Signal;
import org.springframework.stereotype.Repository;

/** Spring Data JDBC implementation of the {@link SignalRepository} port. */
@Repository
class SignalRepositoryAdapter implements SignalRepository {

  private final SignalCrudRepository signalCrudRepository;

  SignalRepositoryAdapter(SignalCrudRepository signalCrudRepository) {
    this.signalCrudRepository = signalCrudRepository;
  }

  @Override
  public Signal save(Signal signal) {
    return toDomain(signalCrudRepository.save(toRow(signal)));
  }

  @Override
  public Optional<Signal> findById(UUID id) {
    return signalCrudRepository.findById(id).map(SignalRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<Signal> findByRelevantInformationId(UUID relevantInformationId) {
    return signalCrudRepository
        .findByRelevantInformationId(relevantInformationId)
        .map(SignalRepositoryAdapter::toDomain);
  }

  @Override
  public List<Signal> findMostRecent(int maxResults) {
    return signalCrudRepository.findMostRecent(maxResults).stream()
        .map(SignalRepositoryAdapter::toDomain)
        .toList();
  }

  private static SignalRow toRow(Signal signal) {
    return new SignalRow(
        signal.id(),
        signal.relevantInformationId(),
        signal.state(),
        signal.createdAt(),
        signal.updatedAt());
  }

  private static Signal toDomain(SignalRow row) {
    return new Signal(
        row.id(), row.relevantInformationId(), row.state(), row.createdAt(), row.updatedAt());
  }
}
