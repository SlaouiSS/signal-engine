package org.signalengine.interfaces.rest.signal;

import java.time.Instant;
import java.util.UUID;
import org.signalengine.domain.Feedback;
import org.signalengine.domain.FeedbackVerdict;

/** API representation of a recorded feedback entry (docs/05-data-model.md Section 12). */
public record FeedbackResponse(UUID id, UUID signalId, FeedbackVerdict verdict, Instant createdAt) {

  public static FeedbackResponse from(Feedback feedback) {
    return new FeedbackResponse(
        feedback.id(), feedback.signalId(), feedback.verdict(), feedback.createdAt());
  }
}
