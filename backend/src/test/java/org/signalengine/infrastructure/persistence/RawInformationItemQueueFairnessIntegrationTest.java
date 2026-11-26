package org.signalengine.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A page of pending work puts an item that failed transiently behind the items not attempted yet,
 * against the real SQL ordering — so items that keep failing cannot starve the rest of the queue
 * (docs/05-data-model.md Section 14; docs/adr/0018-pipeline-reliability.md).
 */
class RawInformationItemQueueFairnessIntegrationTest extends AbstractPersistenceIntegrationTest {

  @Autowired private SourceRepository sources;
  @Autowired private RawInformationItemRepository rawItems;

  private UUID sourceId() {
    return sources
        .save(
            new Source(
                null, "http", "Src", "https://ex.test/" + UUID.randomUUID(), true, null, null))
        .id();
  }

  private RawInformationItem pendingItem(UUID sourceId, String state, Instant at) {
    return rawItems.save(
        new RawInformationItem(
            null,
            sourceId,
            null,
            "hash-" + UUID.randomUUID(),
            null,
            "raw",
            "text " + UUID.randomUUID(),
            null,
            null,
            at,
            new ProcessingState(state, null, null, null, at),
            null,
            null,
            null));
  }

  private static List<UUID> ids(List<RawInformationItem> items) {
    return items.stream().map(RawInformationItem::id).toList();
  }

  @Test
  void aTransientlyFailedItemMovesBehindTheItemsNotAttemptedYetInASingleStatePage() {
    UUID sourceId = sourceId();
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    RawInformationItem oldest = pendingItem(sourceId, ProcessingState.NORMALIZED, base);
    RawInformationItem newer =
        pendingItem(sourceId, ProcessingState.NORMALIZED, base.plusSeconds(60));

    List<UUID> before =
        ids(rawItems.findDueByProcessingState(ProcessingState.NORMALIZED, Instant.now(), 500));
    assertThat(before.indexOf(oldest.id())).isLessThan(before.indexOf(newer.id()));

    boolean recorded =
        rawItems.recordRetryableAttempt(
            oldest.id(), ProcessingState.NORMALIZED, base.plusSeconds(3600));

    List<UUID> after =
        ids(rawItems.findDueByProcessingState(ProcessingState.NORMALIZED, Instant.now(), 500));
    assertThat(recorded).isTrue();
    assertThat(after.indexOf(newer.id())).isLessThan(after.indexOf(oldest.id()));
  }

  @Test
  void recordingAnAttemptDoesNotChangeTheStateOfTheItem() {
    UUID sourceId = sourceId();
    RawInformationItem item =
        pendingItem(sourceId, ProcessingState.NORMALIZED, Instant.parse("2026-01-01T00:00:00Z"));

    rawItems.recordRetryableAttempt(
        item.id(), ProcessingState.NORMALIZED, Instant.parse("2026-01-02T00:00:00Z"));

    RawInformationItem reloaded = rawItems.findById(item.id()).orElseThrow();
    assertThat(reloaded.processingState().state()).isEqualTo(ProcessingState.NORMALIZED);
    assertThat(reloaded.processingState().failureReason()).isNull();
  }

  @Test
  void anAttemptIsNotRecordedForAnItemThatAnotherRunAlreadyMovedOn() {
    UUID sourceId = sourceId();
    RawInformationItem item =
        pendingItem(sourceId, ProcessingState.RELEVANT, Instant.parse("2026-01-01T00:00:00Z"));

    boolean recorded =
        rawItems.recordRetryableAttempt(
            item.id(), ProcessingState.DEDUPLICATED, Instant.parse("2026-01-02T00:00:00Z"));

    assertThat(recorded).isFalse();
  }
}
