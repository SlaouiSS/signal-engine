package org.signalengine.application.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.ingestion.CollectionOutcome.Collected;
import org.signalengine.application.ingestion.CollectionOutcome.CollectionFailed;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.domain.ActivityRecord;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;

class DefaultCollectFromSourceUseCaseTest {

  private final SourceRepository sources = mock(SourceRepository.class);
  private final SourceCollectorRegistry registry = mock(SourceCollectorRegistry.class);
  private final SourceCollector collector = mock(SourceCollector.class);
  private final RawInformationItemRepository rawItems = mock(RawInformationItemRepository.class);
  private final ActivityRecordRepository activity = mock(ActivityRecordRepository.class);
  private final ContentNormalizer normalizer = new DeterministicContentNormalizer();
  private final Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

  private final DefaultCollectFromSourceUseCase useCase =
      new DefaultCollectFromSourceUseCase(sources, registry, normalizer, rawItems, activity, clock);

  private static Source enabledSource(UUID id) {
    return new Source(id, "http", "Feed", "https://feed.example.test/rss", true, null, null);
  }

  @Test
  void collectsAnEnabledSourceAndPersistsANewRawInformationItem() {
    UUID id = UUID.randomUUID();
    Source source = enabledSource(id);
    when(sources.findById(id)).thenReturn(Optional.of(source));
    when(registry.collectorFor(source)).thenReturn(Optional.of(collector));
    when(collector.collect(source))
        .thenReturn(
            new Collected(
                List.of(
                    new CollectedItem(
                        "ext-1",
                        "https://feed.example.test/a",
                        "A title",
                        Instant.parse("2025-12-01T00:00:00Z"),
                        "  Raw body\r\n\r\n",
                        "text/html"))));
    when(rawItems.findByIdentity(any(), any(), any())).thenReturn(Optional.empty());
    when(rawItems.saveIfNew(any())).thenReturn(true);

    CollectionReport report = useCase.collectFromSource(id);

    assertThat(report.status()).isEqualTo(CollectionReport.Status.COLLECTED);
    assertThat(report.newItemCount()).isEqualTo(1);
    assertThat(report.duplicateCount()).isZero();

    ArgumentCaptor<RawInformationItem> saved = ArgumentCaptor.forClass(RawInformationItem.class);
    verify(rawItems).saveIfNew(saved.capture());
    RawInformationItem item = saved.getValue();
    assertThat(item.sourceId()).isEqualTo(id);
    assertThat(item.sourceProvidedId()).isEqualTo("ext-1");
    assertThat(item.originalUrl()).isEqualTo("https://feed.example.test/a");
    assertThat(item.publishedAt()).isEqualTo(Instant.parse("2025-12-01T00:00:00Z"));
    assertThat(item.rawContent()).isEqualTo("  Raw body\r\n\r\n");
    assertThat(item.normalizedContent()).isEqualTo("Raw body");
    assertThat(item.contentHash()).isNotBlank();
    assertThat(item.collectedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
    assertThat(item.processingState().state()).isEqualTo(ProcessingState.NORMALIZED);
  }

  @Test
  void doesNotCollectADisabledSource() {
    UUID id = UUID.randomUUID();
    Source disabled =
        new Source(id, "http", "Feed", "https://feed.example.test/rss", false, null, null);
    when(sources.findById(id)).thenReturn(Optional.of(disabled));

    CollectionReport report = useCase.collectFromSource(id);

    assertThat(report.status()).isEqualTo(CollectionReport.Status.SKIPPED_DISABLED);
    verifyNoInteractions(registry, rawItems);
    verify(activity, never()).save(any());
  }

  @Test
  void reportsWhenNoCollectorIsRegisteredForTheSourceType() {
    UUID id = UUID.randomUUID();
    Source source = enabledSource(id);
    when(sources.findById(id)).thenReturn(Optional.of(source));
    when(registry.collectorFor(source)).thenReturn(Optional.empty());

    CollectionReport report = useCase.collectFromSource(id);

    assertThat(report.status()).isEqualTo(CollectionReport.Status.NO_COLLECTOR);
    verify(rawItems, never()).saveIfNew(any());
    verify(activity).save(any());
  }

  @Test
  void recordsACollectionFailureWithoutSwallowingIt() {
    UUID id = UUID.randomUUID();
    Source source = enabledSource(id);
    when(sources.findById(id)).thenReturn(Optional.of(source));
    when(registry.collectorFor(source)).thenReturn(Optional.of(collector));
    when(collector.collect(source)).thenReturn(new CollectionFailed("source unreachable", true));

    CollectionReport report = useCase.collectFromSource(id);

    assertThat(report.status()).isEqualTo(CollectionReport.Status.FAILED);
    assertThat(report.failureReason()).isEqualTo("source unreachable");
    ArgumentCaptor<ActivityRecord> recorded = ArgumentCaptor.forClass(ActivityRecord.class);
    verify(activity).save(recorded.capture());
    assertThat(recorded.getValue().outcome()).isEqualTo("failure");
    verify(rawItems, never()).saveIfNew(any());
  }

  @Test
  void doesNotPersistAnExactDuplicateTwice() {
    UUID id = UUID.randomUUID();
    Source source = enabledSource(id);
    when(sources.findById(id)).thenReturn(Optional.of(source));
    when(registry.collectorFor(source)).thenReturn(Optional.of(collector));
    when(collector.collect(source))
        .thenReturn(
            new Collected(
                List.of(new CollectedItem(null, null, null, null, "same body", "text/plain"))));
    when(rawItems.findByIdentity(any(), any(), any()))
        .thenReturn(
            Optional.of(
                new RawInformationItem(
                    UUID.randomUUID(),
                    id,
                    null,
                    "hash",
                    null,
                    "same body",
                    "same body",
                    null,
                    null,
                    Instant.now(),
                    ProcessingState.normalized(Instant.now()),
                    null,
                    null,
                    null)));

    CollectionReport report = useCase.collectFromSource(id);

    assertThat(report.status()).isEqualTo(CollectionReport.Status.COLLECTED);
    assertThat(report.newItemCount()).isZero();
    assertThat(report.duplicateCount()).isEqualTo(1);
    verify(rawItems, never()).saveIfNew(any());
  }

  @Test
  void anExactDuplicateLostAtTheInsertRaceIsCountedAsADuplicateNotAFailure() {
    UUID id = UUID.randomUUID();
    Source source = enabledSource(id);
    when(sources.findById(id)).thenReturn(Optional.of(source));
    when(registry.collectorFor(source)).thenReturn(Optional.of(collector));
    when(collector.collect(source))
        .thenReturn(
            new Collected(
                List.of(new CollectedItem("ext-7", null, null, null, "body", "text/plain"))));
    // fast path misses (concurrent run has not committed yet)...
    when(rawItems.findByIdentity(any(), any(), any())).thenReturn(Optional.empty());
    // ...and the database identity constraint rejects this insert as a duplicate.
    when(rawItems.saveIfNew(any())).thenReturn(false);

    CollectionReport report = useCase.collectFromSource(id);

    assertThat(report.status()).isEqualTo(CollectionReport.Status.COLLECTED);
    assertThat(report.newItemCount()).isZero();
    assertThat(report.duplicateCount()).isEqualTo(1);
  }

  @Test
  void deduplicationLookupUsesTheApplicationPort() {
    UUID id = UUID.randomUUID();
    Source source = enabledSource(id);
    when(sources.findById(id)).thenReturn(Optional.of(source));
    when(registry.collectorFor(source)).thenReturn(Optional.of(collector));
    when(collector.collect(source))
        .thenReturn(
            new Collected(
                List.of(new CollectedItem("ext-9", null, null, null, "body", "text/plain"))));
    when(rawItems.findByIdentity(any(), any(), any())).thenReturn(Optional.empty());
    when(rawItems.saveIfNew(any())).thenReturn(true);

    useCase.collectFromSource(id);

    verify(rawItems).findByIdentity(eq(id), eq("ext-9"), any());
  }

  @Test
  void anUnknownSourceIdIsRejected() {
    UUID id = UUID.randomUUID();
    when(sources.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.collectFromSource(id))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  void collectFromEnabledSourcesSkipsDisabledAndIsolatesAFailingSource() {
    Source ok = enabledSource(UUID.randomUUID());
    Source broken = enabledSource(UUID.randomUUID());
    Source disabled =
        new Source(UUID.randomUUID(), "http", "d", "https://d.example.test", false, null, null);
    when(sources.findAll()).thenReturn(List.of(ok, broken, disabled));
    when(sources.findById(ok.id())).thenReturn(Optional.of(ok));
    when(sources.findById(broken.id())).thenReturn(Optional.of(broken));
    when(registry.collectorFor(ok)).thenReturn(Optional.of(collector));
    when(registry.collectorFor(broken)).thenReturn(Optional.of(collector));
    when(collector.collect(ok))
        .thenReturn(
            new Collected(
                List.of(new CollectedItem(null, null, null, null, "body", "text/plain"))));
    when(collector.collect(broken)).thenThrow(new RuntimeException("boom"));
    when(rawItems.findByIdentity(any(), any(), any())).thenReturn(Optional.empty());
    when(rawItems.saveIfNew(any())).thenReturn(true);

    List<CollectionReport> reports = useCase.collectFromEnabledSources();

    assertThat(reports).hasSize(2);
    assertThat(reports)
        .extracting(CollectionReport::status)
        .containsExactlyInAnyOrder(
            CollectionReport.Status.COLLECTED, CollectionReport.Status.FAILED);
  }
}
