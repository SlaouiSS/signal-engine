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
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.domain.ActivityRecord;

class DefaultReviewActivityUseCaseTest {

  private final ActivityRecordRepository activityRecordRepository =
      mock(ActivityRecordRepository.class);
  private final DefaultReviewActivityUseCase useCase =
      new DefaultReviewActivityUseCase(activityRecordRepository);

  @Test
  void listsTheMostRecentActivity() {
    List<ActivityRecord> recent = List.of(sampleActivityRecord(), sampleActivityRecord());
    when(activityRecordRepository.findMostRecent(20)).thenReturn(recent);

    assertThat(useCase.listRecentActivity(20)).isEqualTo(recent);
  }

  @Test
  void rejectsANonPositiveLimit() {
    assertThatThrownBy(() -> useCase.listRecentActivity(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void findsAnActivityRecordById() {
    ActivityRecord activityRecord = sampleActivityRecord();
    when(activityRecordRepository.findById(activityRecord.id()))
        .thenReturn(Optional.of(activityRecord));

    assertThat(useCase.findActivityRecord(activityRecord.id())).contains(activityRecord);
  }

  private static ActivityRecord sampleActivityRecord() {
    return new ActivityRecord(
        UUID.randomUUID(), Instant.now(), "collection", "success", "collected 3 items", null, null);
  }
}
