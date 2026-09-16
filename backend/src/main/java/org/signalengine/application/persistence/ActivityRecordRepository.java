package org.signalengine.application.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.ActivityRecord;

/**
 * Persistence contract for {@link ActivityRecord} (docs/05-data-model.md Section 13). Append-only.
 */
public interface ActivityRecordRepository {

  ActivityRecord save(ActivityRecord activityRecord);

  Optional<ActivityRecord> findById(UUID id);

  /**
   * The most recent activity records, newest first — the bounded activity feed the user browses
   * (docs/05-data-model.md Section 13; docs/02-functional-spec.md Section 15.1). Ordering is by
   * occurrence time and is not an open question.
   */
  List<ActivityRecord> findMostRecent(int maxResults);
}
