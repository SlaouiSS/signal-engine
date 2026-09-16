package org.signalengine.application.usecase;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.persistence.FeedbackRepository;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.domain.Feedback;
import org.signalengine.domain.FeedbackVerdict;
import org.signalengine.domain.SignalState;

/** Default implementation of {@link SubmitFeedbackUseCase}. */
public final class DefaultSubmitFeedbackUseCase implements SubmitFeedbackUseCase {

  private final FeedbackRepository feedbackRepository;
  private final SignalRepository signalRepository;
  private final UnitOfWork unitOfWork;

  public DefaultSubmitFeedbackUseCase(
      FeedbackRepository feedbackRepository,
      SignalRepository signalRepository,
      UnitOfWork unitOfWork) {
    this.feedbackRepository = feedbackRepository;
    this.signalRepository = signalRepository;
    this.unitOfWork = unitOfWork;
  }

  @Override
  public Optional<Feedback> submitFeedback(UUID signalId, FeedbackVerdict verdict) {
    Objects.requireNonNull(signalId, "signalId");
    Objects.requireNonNull(verdict, "verdict");
    return unitOfWork.inTransaction(
        () ->
            signalRepository
                .findById(signalId)
                .map(
                    signal -> {
                      Feedback recorded =
                          feedbackRepository.save(new Feedback(null, signalId, verdict, null));
                      signalRepository.save(signal.withState(reviewStateFor(verdict)));
                      return recorded;
                    }));
  }

  /**
   * The signal state a verdict implies (docs/02-functional-spec.md Section 9.3): a relevant signal
   * is kept, a not-relevant one is dismissed.
   */
  private static SignalState reviewStateFor(FeedbackVerdict verdict) {
    return switch (verdict) {
      case RELEVANT -> SignalState.KEPT;
      case NOT_RELEVANT -> SignalState.DISMISSED;
    };
  }
}
