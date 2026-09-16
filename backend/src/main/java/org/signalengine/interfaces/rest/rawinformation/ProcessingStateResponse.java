package org.signalengine.interfaces.rest.rawinformation;

import java.time.Instant;
import org.signalengine.domain.ProcessingState;

/**
 * API representation of a raw information item's position in the processing pipeline
 * (docs/05-data-model.md Section 14). {@code state} is free text; the failure fields are populated
 * only when the item is in a failed state.
 */
public record ProcessingStateResponse(
    String state,
    String failedStage,
    String failureReason,
    Boolean failureRetryable,
    Instant updatedAt) {

  public static ProcessingStateResponse from(ProcessingState processingState) {
    return new ProcessingStateResponse(
        processingState.state(),
        processingState.failedStage(),
        processingState.failureReason(),
        processingState.failureRetryable(),
        processingState.updatedAt());
  }
}
