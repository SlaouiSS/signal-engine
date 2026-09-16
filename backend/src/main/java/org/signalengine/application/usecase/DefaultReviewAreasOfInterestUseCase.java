package org.signalengine.application.usecase;

import java.util.List;
import java.util.Optional;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.domain.AreaOfInterest;

/** Default implementation of {@link ReviewAreasOfInterestUseCase}. */
public final class DefaultReviewAreasOfInterestUseCase implements ReviewAreasOfInterestUseCase {

  private final AreaOfInterestRepository areaOfInterestRepository;

  public DefaultReviewAreasOfInterestUseCase(AreaOfInterestRepository areaOfInterestRepository) {
    this.areaOfInterestRepository = areaOfInterestRepository;
  }

  @Override
  public List<AreaOfInterest> listAreasOfInterest() {
    return areaOfInterestRepository.findAll();
  }

  @Override
  public Optional<AreaOfInterest> findAreaOfInterest(String areaOfInterestCode) {
    return areaOfInterestRepository.findByCode(areaOfInterestCode);
  }
}
