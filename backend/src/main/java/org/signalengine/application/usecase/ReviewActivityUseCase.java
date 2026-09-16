package org.signalengine.application.usecase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.ActivityRecord;

/**
 * Browse what the system did — the bounded activity feed (docs/02-functional-spec.md Section 15.1,
 * workflow W11; docs/05-data-model.md Section 13).
 */
public interface ReviewActivityUseCase {

  /** The most recent activity records, newest first. */
  List<ActivityRecord> listRecentActivity(int maxResults);

  Optional<ActivityRecord> findActivityRecord(UUID activityRecordId);
}
