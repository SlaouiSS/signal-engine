package org.signalengine.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;

class DefaultReviewRawInformationUseCaseTest {

  private final RawInformationItemRepository rawInformationItemRepository =
      mock(RawInformationItemRepository.class);
  private final DefaultReviewRawInformationUseCase useCase =
      new DefaultReviewRawInformationUseCase(rawInformationItemRepository);

  @Test
  void findsARawInformationItemById() {
    RawInformationItem item = rawItem(UUID.randomUUID());
    when(rawInformationItemRepository.findById(item.id())).thenReturn(Optional.of(item));

    assertThat(useCase.findRawInformationItem(item.id())).contains(item);
  }

  @Test
  void listsTheRawItemsContributingToARelevantInformationRecord() {
    UUID relevantInformationId = UUID.randomUUID();
    List<RawInformationItem> contributors =
        List.of(rawItem(UUID.randomUUID()), rawItem(UUID.randomUUID()));
    when(rawInformationItemRepository.findByRelevantInformationId(relevantInformationId))
        .thenReturn(contributors);

    assertThat(useCase.listContributingRawInformationItems(relevantInformationId))
        .isEqualTo(contributors);
  }

  private static RawInformationItem rawItem(UUID id) {
    Instant now = Instant.now();
    return new RawInformationItem(
        id,
        UUID.randomUUID(),
        null,
        "hash",
        null,
        null,
        null,
        null,
        null,
        now,
        new ProcessingState("received", null, null, null, now),
        null,
        now,
        now);
  }
}
