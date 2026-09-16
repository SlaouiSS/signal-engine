package org.signalengine.application.dedup;

import java.util.UUID;

/**
 * What one call to {@link GroupIntoRelevantInformationUseCase#groupRawInformationItem(UUID)} did —
 * the value a scheduler (or Task 7) inspects.
 *
 * <p>{@code relevantInformationId} is set for {@code ASSOCIATED_WITH_EXISTING} and {@code
 * CREATED_NEW}; {@code detail} carries the AI's reason or the failure reason where relevant.
 */
public record GroupingReport(
    UUID rawInformationItemId, Outcome outcome, UUID relevantInformationId, String detail) {

  public enum Outcome {
    /** The item corroborates an existing Relevant Information record; it was attached. */
    ASSOCIATED_WITH_EXISTING,
    /** The item is semantically distinct; a new Relevant Information record was created. */
    CREATED_NEW,
    /** The item was not awaiting near-duplicate assessment (already grouped, or failed). */
    SKIPPED_ALREADY_ASSESSED,
    /** The AI assessment failed; the item is untouched (retryable) or marked failed. */
    ASSESSMENT_FAILED
  }

  static GroupingReport associatedWithExisting(
      UUID rawInformationItemId, UUID relevantInformationId, String reason) {
    return new GroupingReport(
        rawInformationItemId, Outcome.ASSOCIATED_WITH_EXISTING, relevantInformationId, reason);
  }

  static GroupingReport createdNew(UUID rawInformationItemId, UUID relevantInformationId) {
    return new GroupingReport(
        rawInformationItemId, Outcome.CREATED_NEW, relevantInformationId, null);
  }

  static GroupingReport skippedAlreadyAssessed(UUID rawInformationItemId, String currentState) {
    return new GroupingReport(
        rawInformationItemId, Outcome.SKIPPED_ALREADY_ASSESSED, null, currentState);
  }

  static GroupingReport assessmentFailed(UUID rawInformationItemId, String reason) {
    return new GroupingReport(rawInformationItemId, Outcome.ASSESSMENT_FAILED, null, reason);
  }
}
