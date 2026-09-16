package org.signalengine.application.usecase;

import static org.signalengine.application.usecase.Guards.requireText;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.application.persistence.InterestRepository;
import org.signalengine.domain.Interest;

/** Default implementation of {@link ManageInterestsUseCase}. */
public final class DefaultManageInterestsUseCase implements ManageInterestsUseCase {

  private final InterestRepository interestRepository;
  private final AreaOfInterestRepository areaOfInterestRepository;

  public DefaultManageInterestsUseCase(
      InterestRepository interestRepository, AreaOfInterestRepository areaOfInterestRepository) {
    this.interestRepository = interestRepository;
    this.areaOfInterestRepository = areaOfInterestRepository;
  }

  @Override
  public Interest addInterest(String areaOfInterestCode, String description) {
    requireText(areaOfInterestCode, "area of interest code");
    requireText(description, "interest description");
    if (areaOfInterestRepository.findByCode(areaOfInterestCode).isEmpty()) {
      throw new InvalidInputException("unknown area of interest: " + areaOfInterestCode);
    }
    return interestRepository.save(
        new Interest(null, areaOfInterestCode, description, true, null, null));
  }

  @Override
  public List<Interest> listInterests() {
    return interestRepository.findAll();
  }

  @Override
  public Optional<Interest> findInterest(UUID interestId) {
    return interestRepository.findById(interestId);
  }

  @Override
  public Optional<Interest> updateInterestDescription(UUID interestId, String description) {
    requireText(description, "interest description");
    return interestRepository
        .findById(interestId)
        .map(interest -> interestRepository.save(interest.withDescription(description)));
  }

  @Override
  public Optional<Interest> disableInterest(UUID interestId) {
    return changeEnablement(interestId, false);
  }

  @Override
  public Optional<Interest> enableInterest(UUID interestId) {
    return changeEnablement(interestId, true);
  }

  private Optional<Interest> changeEnablement(UUID interestId, boolean enabled) {
    return interestRepository
        .findById(interestId)
        .map(interest -> interestRepository.save(interest.withEnabled(enabled)));
  }
}
