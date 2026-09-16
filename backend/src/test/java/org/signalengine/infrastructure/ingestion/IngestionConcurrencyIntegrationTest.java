package org.signalengine.infrastructure.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ingestion.CollectedItem;
import org.signalengine.application.ingestion.CollectionOutcome.Collected;
import org.signalengine.application.ingestion.CollectionReport;
import org.signalengine.application.ingestion.DefaultCollectFromSourceUseCase;
import org.signalengine.application.ingestion.DeterministicContentNormalizer;
import org.signalengine.application.ingestion.SourceCollector;
import org.signalengine.application.ingestion.SourceCollectorRegistry;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Exact-deduplication under <em>concurrent</em> collection against a real PostgreSQL: the {@code
 * raw_information_item_identity_key} unique constraint is the final authority, and the collection
 * that loses the insert race is recognised as an exact duplicate — the same idempotent outcome as a
 * sequential re-collection — not a persistence failure (docs/08-ingestion.md Section 15;
 * docs/05-data-model.md Section 17–18).
 */
class IngestionConcurrencyIntegrationTest extends AbstractPersistenceIntegrationTest {

  @Autowired private SourceRepository sources;
  @Autowired private RawInformationItemRepository rawItems;
  @Autowired private ActivityRecordRepository activity;

  private final Clock clock = Clock.fixed(Instant.parse("2026-02-03T04:05:06Z"), ZoneOffset.UTC);

  private Source newSource() {
    return sources.save(
        new Source(
            null, "http", "Feed", "https://ex.test/rss-" + UUID.randomUUID(), true, null, null));
  }

  private DefaultCollectFromSourceUseCase useCaseCollecting(
      CollectedItem item, Runnable beforeYield) {
    SourceCollector collector =
        source -> {
          beforeYield.run();
          return new Collected(List.of(item));
        };
    SourceCollectorRegistry registry = source -> Optional.of(collector);
    return new DefaultCollectFromSourceUseCase(
        sources, registry, new DeterministicContentNormalizer(), rawItems, activity, clock);
  }

  private static RawInformationItem rawItem(UUID sourceId) {
    Instant now = Instant.parse("2026-02-03T04:05:06Z");
    return new RawInformationItem(
        null,
        sourceId,
        "ext-1",
        "the-same-content-hash",
        "https://ex.test/a",
        "body",
        "body",
        null,
        null,
        now,
        ProcessingState.normalized(now),
        null,
        null,
        null);
  }

  private static <T> List<T> runConcurrently(Callable<T> a, Callable<T> b) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CyclicBarrier startTogether = new CyclicBarrier(2);
      Future<T> first = pool.submit(guardedByBarrier(a, startTogether));
      Future<T> second = pool.submit(guardedByBarrier(b, startTogether));
      return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  private static <T> Callable<T> guardedByBarrier(Callable<T> work, CyclicBarrier barrier) {
    return () -> {
      barrier.await(10, TimeUnit.SECONDS);
      return work.call();
    };
  }

  @Test
  void twoConcurrentSaveIfNewCallsForTheSameIdentityPersistOneRowAndTheLoserReportsDuplicate()
      throws Exception {
    UUID sourceId = newSource().id();
    RawInformationItem item = rawItem(sourceId);

    List<Boolean> results =
        runConcurrently(() -> rawItems.saveIfNew(item), () -> rawItems.saveIfNew(item));

    // exactly one insert won, the other was recognised as an exact duplicate — never an exception
    assertThat(results).containsExactlyInAnyOrder(true, false);
    assertThat(rawItems.findByIdentity(sourceId, "ext-1", "the-same-content-hash")).isPresent();
    assertThat(rawItems.findByProcessingState(ProcessingState.NORMALIZED, 100))
        .filteredOn(persisted -> persisted.sourceId().equals(sourceId))
        .hasSize(1);
  }

  @Test
  void concurrentCollectionOfTheSameItemConvergesToOneItemAndBothCollectionsSucceed()
      throws Exception {
    UUID sourceId = newSource().id();
    CollectedItem collected =
        new CollectedItem(
            "ext-1",
            "https://ex.test/a",
            "Title",
            Instant.parse("2026-01-15T09:00:00Z"),
            "Article body",
            "text/html");
    CyclicBarrier bothInsideCollect = new CyclicBarrier(2);
    DefaultCollectFromSourceUseCase useCase =
        useCaseCollecting(collected, () -> awaitQuietly(bothInsideCollect));

    List<CollectionReport> reports =
        runConcurrently(
            () -> useCase.collectFromSource(sourceId), () -> useCase.collectFromSource(sourceId));

    assertThat(reports)
        .allSatisfy(
            report -> assertThat(report.status()).isEqualTo(CollectionReport.Status.COLLECTED));
    assertThat(reports.stream().mapToInt(CollectionReport::newItemCount).sum()).isEqualTo(1);
    assertThat(reports.stream().mapToInt(CollectionReport::duplicateCount).sum()).isEqualTo(1);

    assertThat(rawItems.findByProcessingState(ProcessingState.NORMALIZED, 100))
        .filteredOn(persisted -> persisted.sourceId().equals(sourceId))
        .hasSize(1);
    assertThat(activity.findMostRecent(50))
        .filteredOn(record -> sourceId.equals(record.sourceId()))
        .noneMatch(record -> record.outcome().equals("failure"));
  }

  @Test
  void saveIfNewPropagatesAnUnrelatedIntegrityViolationAsAFailure() {
    // a foreign-key violation (unknown source) is SQLState 23503, not the identity constraint
    RawInformationItem itemForAMissingSource = rawItem(UUID.randomUUID());

    assertThatThrownBy(() -> rawItems.saveIfNew(itemForAMissingSource))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static void awaitQuietly(CyclicBarrier barrier) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (Exception interruptedOrTimedOut) {
      throw new IllegalStateException(interruptedOrTimedOut);
    }
  }
}
