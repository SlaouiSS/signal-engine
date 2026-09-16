package org.signalengine.application.signal;

import java.util.UUID;

/**
 * What one call to {@link ProcessRelevantInformationUseCase#process(UUID)} did — the value a
 * scheduler (or Task 8) inspects.
 *
 * <p>{@code signalId} is set for {@code SIGNAL_WITH_SUMMARY} and {@code SIGNAL_SUMMARY_PENDING};
 * {@code detail} carries the AI's reason or the failure reason where relevant.
 */
public record ProcessingReport(
    UUID relevantInformationId, Outcome outcome, UUID signalId, String detail) {

  public enum Outcome {
    /** Relevance assessment found it not relevant; set aside as noise, no Signal. */
    NOT_RELEVANT,
    /** Relevant, but importance assessment did not warrant a Signal. */
    RELEVANT_NOT_IMPORTANT,
    /** A Signal was created and a source-grounded Summary generated. */
    SIGNAL_WITH_SUMMARY,
    /** A Signal exists; summary generation failed and will be retried. */
    SIGNAL_SUMMARY_PENDING,
    /** An AI step failed (retryable: item untouched; non-retryable: item marked failed). */
    ASSESSMENT_FAILED,
    /** Nothing to do — already terminal, already complete, or another run handled it. */
    SKIPPED
  }

  static ProcessingReport notRelevant(UUID relevantInformationId, String reason) {
    return new ProcessingReport(relevantInformationId, Outcome.NOT_RELEVANT, null, reason);
  }

  static ProcessingReport relevantNotImportant(UUID relevantInformationId, String reason) {
    return new ProcessingReport(
        relevantInformationId, Outcome.RELEVANT_NOT_IMPORTANT, null, reason);
  }

  static ProcessingReport signalWithSummary(UUID relevantInformationId, UUID signalId) {
    return new ProcessingReport(relevantInformationId, Outcome.SIGNAL_WITH_SUMMARY, signalId, null);
  }

  static ProcessingReport signalSummaryPending(
      UUID relevantInformationId, UUID signalId, String detail) {
    return new ProcessingReport(
        relevantInformationId, Outcome.SIGNAL_SUMMARY_PENDING, signalId, detail);
  }

  static ProcessingReport assessmentFailed(UUID relevantInformationId, String detail) {
    return new ProcessingReport(relevantInformationId, Outcome.ASSESSMENT_FAILED, null, detail);
  }

  static ProcessingReport skipped(UUID relevantInformationId, String currentState) {
    return new ProcessingReport(relevantInformationId, Outcome.SKIPPED, null, currentState);
  }
}
