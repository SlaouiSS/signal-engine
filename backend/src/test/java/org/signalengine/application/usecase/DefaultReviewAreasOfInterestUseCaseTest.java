package org.signalengine.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.domain.AreaOfInterest;

class DefaultReviewAreasOfInterestUseCaseTest {

  private final AreaOfInterestRepository areaOfInterestRepository =
      mock(AreaOfInterestRepository.class);
  private final DefaultReviewAreasOfInterestUseCase useCase =
      new DefaultReviewAreasOfInterestUseCase(areaOfInterestRepository);

  @Test
  void listsAllAreasOfInterest() {
    AreaOfInterest area = new AreaOfInterest("AI_AND_TECHNOLOGY", "AI & Technology");
    when(areaOfInterestRepository.findAll()).thenReturn(List.of(area));

    assertThat(useCase.listAreasOfInterest()).containsExactly(area);
  }

  @Test
  void findsAnAreaByCodeAndReportsAnUnknownCodeAsEmpty() {
    AreaOfInterest area = new AreaOfInterest("LAW_AND_REGULATION", "Law & Regulation");
    when(areaOfInterestRepository.findByCode("LAW_AND_REGULATION")).thenReturn(Optional.of(area));
    when(areaOfInterestRepository.findByCode("NOPE")).thenReturn(Optional.empty());

    assertThat(useCase.findAreaOfInterest("LAW_AND_REGULATION")).contains(area);
    assertThat(useCase.findAreaOfInterest("NOPE")).isEmpty();
  }
}
