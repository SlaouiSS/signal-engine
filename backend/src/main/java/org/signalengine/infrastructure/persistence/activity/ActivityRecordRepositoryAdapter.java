package org.signalengine.infrastructure.persistence.activity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.domain.ActivityRecord;
import org.springframework.stereotype.Repository;

/** Spring Data JDBC implementation of the {@link ActivityRecordRepository} port. */
@Repository
class ActivityRecordRepositoryAdapter implements ActivityRecordRepository {

  private final ActivityRecordCrudRepository activityRecordCrudRepository;

  ActivityRecordRepositoryAdapter(ActivityRecordCrudRepository activityRecordCrudRepository) {
    this.activityRecordCrudRepository = activityRecordCrudRepository;
  }

  @Override
  public ActivityRecord save(ActivityRecord activityRecord) {
    return toDomain(activityRecordCrudRepository.save(toRow(activityRecord)));
  }

  @Override
  public Optional<ActivityRecord> findById(UUID id) {
    return activityRecordCrudRepository.findById(id).map(ActivityRecordRepositoryAdapter::toDomain);
  }

  @Override
  public List<ActivityRecord> findMostRecent(int maxResults) {
    return activityRecordCrudRepository.findMostRecent(maxResults).stream()
        .map(ActivityRecordRepositoryAdapter::toDomain)
        .toList();
  }

  private static ActivityRecordRow toRow(ActivityRecord activityRecord) {
    return new ActivityRecordRow(
        activityRecord.id(),
        activityRecord.occurredAt(),
        activityRecord.category(),
        activityRecord.outcome(),
        activityRecord.message(),
        activityRecord.sourceId(),
        activityRecord.rawInformationItemId());
  }

  private static ActivityRecord toDomain(ActivityRecordRow row) {
    return new ActivityRecord(
        row.id(),
        row.occurredAt(),
        row.category(),
        row.outcome(),
        row.message(),
        row.sourceId(),
        row.rawInformationItemId());
  }
}
