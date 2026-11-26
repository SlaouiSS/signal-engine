package org.signalengine.application.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.signalengine.application.ai.AiCapabilityFailureException;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.signal.IndexReconciliationReport.Outcome;
import org.signalengine.domain.ActivityRecord;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.embedding.EmbeddingException;
import org.signalengine.rag.indexing.IndexingReport;

/**
 * The policy of the knowledge base reconciliation sweep: what each outcome means and how a
 * suspended capability is reported (docs/adr/0018-pipeline-reliability.md).
 */
class DefaultReconcileKnowledgeBaseIndexUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final int BATCH_LIMIT = 20;

  private final KnowledgeBaseIndexer indexer = mock(KnowledgeBaseIndexer.class);
  private final RawInformationItemRepository rawItems = mock(RawInformationItemRepository.class);
  private final SourceRepository sources = mock(SourceRepository.class);
  private final ActivityRecordRepository activity = mock(ActivityRecordRepository.class);

  private final DefaultReconcileKnowledgeBaseIndexUseCase useCase =
      new DefaultReconcileKnowledgeBaseIndexUseCase(
          indexer, rawItems, sources, activity, Clock.fixed(NOW, ZoneOffset.UTC), BATCH_LIMIT);

  private final UUID sourceId = UUID.randomUUID();
  private final Source source =
      new Source(sourceId, "http", "Example", "https://ex.test", true, NOW, NOW);

  private RawInformationItem awaitingItem() {
    RawInformationItem item =
        new RawInformationItem(
            UUID.randomUUID(),
            sourceId,
            null,
            "hash-" + UUID.randomUUID(),
            "https://ex.test/a",
            "raw",
            "text",
            null,
            null,
            NOW,
            ProcessingState.relevant(NOW),
            UUID.randomUUID(),
            NOW,
            NOW);
    when(rawItems.findById(item.id())).thenReturn(Optional.of(item));
    return item;
  }

  private void awaitingIndexing(RawInformationItem... items) {
    when(indexer.findItemIdsAwaitingIndexing(NOW, BATCH_LIMIT))
        .thenReturn(java.util.Arrays.stream(items).map(RawInformationItem::id).toList());
    when(sources.findById(sourceId)).thenReturn(Optional.of(source));
  }

  private static IndexingReport indexed(RawInformationItem item, int passages) {
    return new IndexingReport(
        item.id().toString(),
        passages,
        passages,
        passages,
        0,
        0,
        ComponentDescriptor.unspecified(RagComponentType.INDEXING_PIPELINE),
        List.of(),
        Map.of());
  }

  private static IndexingReport skipped(RawInformationItem item) {
    return new IndexingReport(
        item.id().toString(),
        0,
        0,
        0,
        1,
        0,
        ComponentDescriptor.unspecified(RagComponentType.INDEXING_PIPELINE),
        List.of("already completely indexed"),
        Map.of());
  }

  private static AiError suspended() {
    return new AiError(
        AiError.CAPABILITY_SUSPENDED_CODE,
        AiError.SUSPENDED_CATEGORY,
        true,
        "capability 'embed' is suspended",
        UUID.randomUUID(),
        Map.of());
  }

  private List<ActivityRecord> savedActivity() {
    ArgumentCaptor<ActivityRecord> captor = ArgumentCaptor.forClass(ActivityRecord.class);
    verify(activity, org.mockito.Mockito.atLeast(0)).save(captor.capture());
    return captor.getAllValues();
  }

  @Test
  void indexesTheItemsTheIndexerReportsAsAwaitingIndexing() {
    RawInformationItem item = awaitingItem();
    awaitingIndexing(item);
    when(indexer.index(item, source)).thenReturn(indexed(item, 3));

    List<IndexReconciliationReport> reports = useCase.reconcilePending();

    assertThat(reports)
        .singleElement()
        .satisfies(r -> assertThat(r.outcome()).isEqualTo(Outcome.INDEXED));
    assertThat(savedActivity())
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.outcome()).isEqualTo("success");
              assertThat(record.message()).contains("3 passage(s)");
            });
  }

  @Test
  void anItemAnotherRunIsIndexingIsSkippedWithoutAnActivityRecord() {
    RawInformationItem item = awaitingItem();
    awaitingIndexing(item);
    when(indexer.index(item, source)).thenReturn(skipped(item));

    List<IndexReconciliationReport> reports = useCase.reconcilePending();

    assertThat(reports)
        .singleElement()
        .satisfies(r -> assertThat(r.outcome()).isEqualTo(Outcome.SKIPPED));
    verify(activity, never()).save(any());
  }

  @Test
  void aFailureIsRecordedAndTheSweepCarriesOnWithTheNextItem() {
    RawInformationItem failing = awaitingItem();
    RawInformationItem healthy = awaitingItem();
    awaitingIndexing(failing, healthy);
    when(indexer.index(failing, source)).thenThrow(new EmbeddingException("provider unreachable"));
    when(indexer.index(healthy, source)).thenReturn(indexed(healthy, 1));

    List<IndexReconciliationReport> reports = useCase.reconcilePending();

    assertThat(reports)
        .extracting(IndexReconciliationReport::outcome)
        .containsExactly(Outcome.FAILED, Outcome.INDEXED);
    assertThat(savedActivity())
        .anySatisfy(
            record -> {
              assertThat(record.outcome()).isEqualTo("failure");
              assertThat(record.message()).contains("indexing failed", "provider unreachable");
            });
  }

  @Test
  void aSuspendedCapabilityDoesNotStopTheSweepAndIsSummarisedByOneActivityRecord() {
    RawInformationItem first = awaitingItem();
    RawInformationItem second = awaitingItem();
    awaitingIndexing(first, second);
    when(indexer.index(any(), any()))
        .thenThrow(
            new EmbeddingException(
                "embed suspended", new AiCapabilityFailureException(suspended())));

    List<IndexReconciliationReport> reports = useCase.reconcilePending();

    assertThat(reports)
        .extracting(IndexReconciliationReport::outcome)
        .containsOnly(Outcome.SUSPENDED);
    verify(indexer, times(2)).index(any(), any());
    assertThat(savedActivity())
        .singleElement()
        .satisfies(record -> assertThat(record.message()).contains("2 item(s)", "suspended"));
  }

  @Test
  void anItemWhoseSourceNoLongerExistsIsSkippedWithoutCallingTheIndexer() {
    RawInformationItem item = awaitingItem();
    when(indexer.findItemIdsAwaitingIndexing(NOW, BATCH_LIMIT)).thenReturn(List.of(item.id()));
    when(sources.findById(sourceId)).thenReturn(Optional.empty());

    List<IndexReconciliationReport> reports = useCase.reconcilePending();

    assertThat(reports)
        .singleElement()
        .satisfies(r -> assertThat(r.outcome()).isEqualTo(Outcome.SKIPPED));
    verify(indexer, never()).index(any(), any());
  }

  @Test
  void anItemThatDisappearedBetweenTheBacklogQueryAndTheSweepIsIgnored() {
    UUID vanished = UUID.randomUUID();
    when(indexer.findItemIdsAwaitingIndexing(anyInstant(), anyInt())).thenReturn(List.of(vanished));
    when(rawItems.findById(vanished)).thenReturn(Optional.empty());

    assertThat(useCase.reconcilePending()).isEmpty();
  }

  private static Instant anyInstant() {
    return any(Instant.class);
  }
}
