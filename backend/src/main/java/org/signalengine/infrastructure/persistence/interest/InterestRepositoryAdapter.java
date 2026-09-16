package org.signalengine.infrastructure.persistence.interest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.persistence.InterestRepository;
import org.signalengine.domain.Interest;
import org.springframework.stereotype.Repository;

/** Spring Data JDBC implementation of the {@link InterestRepository} port. */
@Repository
class InterestRepositoryAdapter implements InterestRepository {

  private final InterestCrudRepository interestCrudRepository;

  InterestRepositoryAdapter(InterestCrudRepository interestCrudRepository) {
    this.interestCrudRepository = interestCrudRepository;
  }

  @Override
  public Interest save(Interest interest) {
    return toDomain(interestCrudRepository.save(toRow(interest)));
  }

  @Override
  public Optional<Interest> findById(UUID id) {
    return interestCrudRepository.findById(id).map(InterestRepositoryAdapter::toDomain);
  }

  @Override
  public List<Interest> findAll() {
    return interestCrudRepository.findAll().stream()
        .map(InterestRepositoryAdapter::toDomain)
        .toList();
  }

  private static InterestRow toRow(Interest interest) {
    return new InterestRow(
        interest.id(),
        interest.areaOfInterestCode(),
        interest.description(),
        interest.enabled(),
        interest.createdAt(),
        interest.updatedAt());
  }

  private static Interest toDomain(InterestRow row) {
    return new Interest(
        row.id(),
        row.areaOfInterestCode(),
        row.description(),
        row.enabled(),
        row.createdAt(),
        row.updatedAt());
  }
}
