package org.signalengine.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.application.persistence.InterestRepository;
import org.signalengine.domain.AreaOfInterest;
import org.signalengine.domain.Interest;

class DefaultManageInterestsUseCaseTest {

  private final InterestRepository interestRepository = mock(InterestRepository.class);
  private final AreaOfInterestRepository areaOfInterestRepository =
      mock(AreaOfInterestRepository.class);
  private final DefaultManageInterestsUseCase useCase =
      new DefaultManageInterestsUseCase(interestRepository, areaOfInterestRepository);

  @Test
  void addsAnEnabledInterestUnderAnExistingArea() {
    when(areaOfInterestRepository.findByCode("AI_AND_TECHNOLOGY"))
        .thenReturn(Optional.of(new AreaOfInterest("AI_AND_TECHNOLOGY", "AI & Technology")));
    when(interestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    useCase.addInterest("AI_AND_TECHNOLOGY", "open-weight model releases");

    ArgumentCaptor<Interest> saved = ArgumentCaptor.forClass(Interest.class);
    verify(interestRepository).save(saved.capture());
    assertThat(saved.getValue().areaOfInterestCode()).isEqualTo("AI_AND_TECHNOLOGY");
    assertThat(saved.getValue().description()).isEqualTo("open-weight model releases");
    assertThat(saved.getValue().enabled()).isTrue();
  }

  @Test
  void rejectsAnInterestForAnUnknownArea() {
    when(areaOfInterestRepository.findByCode("NOPE")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.addInterest("NOPE", "something"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(interestRepository, never()).save(any());
  }

  @Test
  void rejectsABlankInterestDescription() {
    assertThatThrownBy(() -> useCase.addInterest("AI_AND_TECHNOLOGY", "   "))
        .isInstanceOf(IllegalArgumentException.class);
    verify(interestRepository, never()).save(any());
  }

  @Test
  void updatesTheDescriptionOfAnExistingInterest() {
    Interest interest = existingInterest();
    when(interestRepository.findById(interest.id())).thenReturn(Optional.of(interest));
    when(interestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    Interest updated =
        useCase.updateInterestDescription(interest.id(), "revised wording").orElseThrow();

    assertThat(updated.description()).isEqualTo("revised wording");
    assertThat(updated.areaOfInterestCode()).isEqualTo(interest.areaOfInterestCode());
  }

  @Test
  void updatingAnUnknownInterestReturnsEmpty() {
    UUID unknownId = UUID.randomUUID();
    when(interestRepository.findById(unknownId)).thenReturn(Optional.empty());

    assertThat(useCase.updateInterestDescription(unknownId, "x")).isEmpty();
  }

  @Test
  void enablesAndDisablesAnInterest() {
    Interest interest = existingInterest();
    when(interestRepository.findById(interest.id())).thenReturn(Optional.of(interest));
    when(interestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    assertThat(useCase.disableInterest(interest.id()).orElseThrow().enabled()).isFalse();
    assertThat(useCase.enableInterest(interest.id()).orElseThrow().enabled()).isTrue();
  }

  private static Interest existingInterest() {
    return new Interest(
        UUID.randomUUID(),
        "AI_AND_TECHNOLOGY",
        "original wording",
        true,
        Instant.now(),
        Instant.now());
  }
}
