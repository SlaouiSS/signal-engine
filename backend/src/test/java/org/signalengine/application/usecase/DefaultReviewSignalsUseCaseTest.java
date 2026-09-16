package org.signalengine.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.domain.Signal;
import org.signalengine.domain.SignalState;

class DefaultReviewSignalsUseCaseTest {

  private final SignalRepository signalRepository = mock(SignalRepository.class);
  private final DefaultReviewSignalsUseCase useCase =
      new DefaultReviewSignalsUseCase(signalRepository);

  @Test
  void opensASignalById() {
    Signal signal = signal(UUID.randomUUID(), UUID.randomUUID());
    when(signalRepository.findById(signal.id())).thenReturn(Optional.of(signal));

    assertThat(useCase.findSignal(signal.id())).contains(signal);
  }

  @Test
  void reachesTheSignalOfARelevantInformationRecord() {
    UUID relevantInformationId = UUID.randomUUID();
    Signal signal = signal(UUID.randomUUID(), relevantInformationId);
    when(signalRepository.findByRelevantInformationId(relevantInformationId))
        .thenReturn(Optional.of(signal));

    assertThat(useCase.findSignalForRelevantInformation(relevantInformationId)).contains(signal);
  }

  @Test
  void listsTheMostRecentSignals() {
    List<Signal> recent = List.of(signal(UUID.randomUUID(), UUID.randomUUID()));
    when(signalRepository.findMostRecent(20)).thenReturn(recent);

    assertThat(useCase.listSignals(20)).isEqualTo(recent);
  }

  @Test
  void rejectsANonPositiveLimit() {
    assertThatThrownBy(() -> useCase.listSignals(0)).isInstanceOf(IllegalArgumentException.class);
  }

  private static Signal signal(UUID id, UUID relevantInformationId) {
    return new Signal(id, relevantInformationId, SignalState.NEW, Instant.now(), Instant.now());
  }
}
