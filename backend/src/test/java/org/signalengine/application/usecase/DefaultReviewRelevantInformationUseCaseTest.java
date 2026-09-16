package org.signalengine.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.domain.RelevantInformation;

class DefaultReviewRelevantInformationUseCaseTest {

  private final RelevantInformationRepository relevantInformationRepository =
      mock(RelevantInformationRepository.class);
  private final DefaultReviewRelevantInformationUseCase useCase =
      new DefaultReviewRelevantInformationUseCase(relevantInformationRepository);

  @Test
  void findsARelevantInformationRecordById() {
    RelevantInformation relevantInformation =
        new RelevantInformation(
            UUID.randomUUID(),
            "mentions the EU AI Act",
            Set.of("LAW_AND_REGULATION"),
            Set.of(),
            Instant.now(),
            Instant.now());
    when(relevantInformationRepository.findById(relevantInformation.id()))
        .thenReturn(Optional.of(relevantInformation));

    assertThat(useCase.findRelevantInformation(relevantInformation.id()))
        .contains(relevantInformation);
  }

  @Test
  void reportsAnUnknownRecordAsEmpty() {
    UUID unknownId = UUID.randomUUID();
    when(relevantInformationRepository.findById(unknownId)).thenReturn(Optional.empty());

    assertThat(useCase.findRelevantInformation(unknownId)).isEmpty();
  }
}
