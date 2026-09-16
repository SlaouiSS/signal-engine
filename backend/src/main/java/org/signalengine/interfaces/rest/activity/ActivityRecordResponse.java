package org.signalengine.interfaces.rest.activity;

import java.time.Instant;
import java.util.UUID;
import org.signalengine.domain.ActivityRecord;

/** API representation of one activity-feed entry (docs/05-data-model.md Section 13). */
public record ActivityRecordResponse(
    UUID id,
    Instant occurredAt,
    String category,
    String outcome,
    String message,
    UUID sourceId,
    UUID rawInformationItemId) {

  public static ActivityRecordResponse from(ActivityRecord activityRecord) {
    return new ActivityRecordResponse(
        activityRecord.id(),
        activityRecord.occurredAt(),
        activityRecord.category(),
        activityRecord.outcome(),
        activityRecord.message(),
        activityRecord.sourceId(),
        activityRecord.rawInformationItemId());
  }
}
