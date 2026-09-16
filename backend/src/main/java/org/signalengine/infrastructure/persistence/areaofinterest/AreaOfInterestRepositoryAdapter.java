package org.signalengine.infrastructure.persistence.areaofinterest;

import java.util.List;
import java.util.Optional;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.domain.AreaOfInterest;
import org.springframework.stereotype.Repository;

/** Spring Data JDBC implementation of the {@link AreaOfInterestRepository} port. */
@Repository
class AreaOfInterestRepositoryAdapter implements AreaOfInterestRepository {

  private final AreaOfInterestCrudRepository areaOfInterestCrudRepository;

  AreaOfInterestRepositoryAdapter(AreaOfInterestCrudRepository areaOfInterestCrudRepository) {
    this.areaOfInterestCrudRepository = areaOfInterestCrudRepository;
  }

  @Override
  public List<AreaOfInterest> findAll() {
    return areaOfInterestCrudRepository.findAll().stream()
        .map(AreaOfInterestRepositoryAdapter::toDomain)
        .toList();
  }

  @Override
  public Optional<AreaOfInterest> findByCode(String code) {
    return areaOfInterestCrudRepository
        .findById(code)
        .map(AreaOfInterestRepositoryAdapter::toDomain);
  }

  private static AreaOfInterest toDomain(AreaOfInterestRow row) {
    return new AreaOfInterest(row.code(), row.name());
  }
}
