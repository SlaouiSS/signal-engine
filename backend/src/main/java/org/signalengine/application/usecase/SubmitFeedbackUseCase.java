package org.signalengine.application.usecase;

import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.Feedback;
import org.signalengine.domain.FeedbackVerdict;

/**
 * Record the user's relevant / not-relevant judgement on a signal (docs/02-functional-spec.md
 * Section 10, workflow W7).
 *
 * <p>Submitting feedback both stores the feedback and moves the signal's state
 * (docs/02-functional-spec.md Section 10.2, "feedback updates the signal's state"; Section 9.3):
 * {@link FeedbackVerdict#RELEVANT} marks the signal {@code KEPT}, {@link
 * FeedbackVerdict#NOT_RELEVANT} marks it {@code DISMISSED}. Both writes commit together.
 */
public interface SubmitFeedbackUseCase {

  /** Records feedback against a signal and updates its state. Empty if no such signal. */
  Optional<Feedback> submitFeedback(UUID signalId, FeedbackVerdict verdict);
}
