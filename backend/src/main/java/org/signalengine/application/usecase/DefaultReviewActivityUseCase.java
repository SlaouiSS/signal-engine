package org.signalengine.application.usecase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.domain.ActivityRecord;

/** Default implementation of {@link ReviewActivityUseCase}. */
public final class DefaultReviewActivityUseCase implements ReviewActivityUseCase {

  private final ActivityRecordRepository activityRecordRepository;

  public DefaultReviewActivityUseCase(ActivityRecordRepository activityRecordRepository) {
    this.activityRecordRepository = activityRecordRepository;
  }

  @Override
  public List<ActivityRecord> listRecentActivity(int maxResults) {
    if (maxResults <= 0) {
      throw new InvalidInputException("maxResults must be positive");
    }
    return activityRecordRepository.findMostRecent(maxResults);
  }

  @Override
  public Optional<ActivityRecord> findActivityRecord(UUID activityRecordId) {
    return activityRecordRepository.findById(activityRecordId);
  }
}
