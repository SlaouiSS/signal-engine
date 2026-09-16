package org.signalengine.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.signalengine.application.persistence.FeedbackRepository;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.domain.Feedback;
import org.signalengine.domain.FeedbackVerdict;
import org.signalengine.domain.Signal;
import org.signalengine.domain.SignalState;

class DefaultSubmitFeedbackUseCaseTest {

  private final FeedbackRepository feedbackRepository = mock(FeedbackRepository.class);
  private final SignalRepository signalRepository = mock(SignalRepository.class);
  private final RecordingUnitOfWork unitOfWork = new RecordingUnitOfWork();
  private final DefaultSubmitFeedbackUseCase useCase =
      new DefaultSubmitFeedbackUseCase(feedbackRepository, signalRepository, unitOfWork);

  @Test
  void relevantFeedbackIsStoredAndKeepsTheSignal() {
    Signal signal = newSignal();
    when(signalRepository.findById(signal.id())).thenReturn(Optional.of(signal));
    when(feedbackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    Feedback recorded = useCase.submitFeedback(signal.id(), FeedbackVerdict.RELEVANT).orElseThrow();

    assertThat(recorded.signalId()).isEqualTo(signal.id());
    assertThat(recorded.verdict()).isEqualTo(FeedbackVerdict.RELEVANT);

    ArgumentCaptor<Signal> savedSignal = ArgumentCaptor.forClass(Signal.class);
    verify(signalRepository).save(savedSignal.capture());
    assertThat(savedSignal.getValue().state()).isEqualTo(SignalState.KEPT);

    assertThat(unitOfWork.wrappedSomething).isTrue();
  }

  @Test
  void notRelevantFeedbackDismissesTheSignal() {
    Signal signal = newSignal();
    when(signalRepository.findById(signal.id())).thenReturn(Optional.of(signal));
    when(feedbackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    useCase.submitFeedback(signal.id(), FeedbackVerdict.NOT_RELEVANT);

    ArgumentCaptor<Signal> savedSignal = ArgumentCaptor.forClass(Signal.class);
    verify(signalRepository).save(savedSignal.capture());
    assertThat(savedSignal.getValue().state()).isEqualTo(SignalState.DISMISSED);
  }

  @Test
  void feedbackForAnUnknownSignalIsRejectedAndNothingIsWritten() {
    UUID unknownSignalId = UUID.randomUUID();
    when(signalRepository.findById(unknownSignalId)).thenReturn(Optional.empty());

    assertThat(useCase.submitFeedback(unknownSignalId, FeedbackVerdict.RELEVANT)).isEmpty();
    verify(feedbackRepository, never()).save(any());
    verify(signalRepository, never()).save(any());
  }

  private static Signal newSignal() {
    return new Signal(
        UUID.randomUUID(), UUID.randomUUID(), SignalState.NEW, Instant.now(), Instant.now());
  }

  /** Runs the work directly, recording that it was wrapped. */
  private static final class RecordingUnitOfWork implements UnitOfWork {
    private boolean wrappedSomething;

    @Override
    public <R> R inTransaction(Supplier<R> work) {
      wrappedSomething = true;
      return work.get();
    }
  }
}
