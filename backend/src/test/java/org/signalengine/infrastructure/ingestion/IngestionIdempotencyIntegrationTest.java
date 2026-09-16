package org.signalengine.infrastructure.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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

/**
 * Exact-deduplication idempotency against a real PostgreSQL: collecting the same source item twice
 * persists exactly one Raw Information Item, with its provenance and processing state
 * (docs/03-technical-spec.md Section 10.4; docs/08-ingestion.md Section 5).
 *
 * <p>The collector is a fixed in-test stub; only normalisation, deduplication, and persistence are
 * under test here. The HTTP collector has its own unit test.
 */
class IngestionIdempotencyIntegrationTest extends AbstractPersistenceIntegrationTest {

  @Autowired private SourceRepository sources;
  @Autowired private RawInformationItemRepository rawItems;
  @Autowired private ActivityRecordRepository activity;

  private final Clock clock = Clock.fixed(Instant.parse("2026-02-03T04:05:06Z"), ZoneOffset.UTC);

  private DefaultCollectFromSourceUseCase useCaseReturning(CollectedItem item) {
    SourceCollector collector = source -> new Collected(List.of(item));
    SourceCollectorRegistry registry =
        new SourceCollectorRegistry() {
          @Override
          public java.util.Optional<SourceCollector> collectorFor(Source source) {
            return java.util.Optional.of(collector);
          }
        };
    return new DefaultCollectFromSourceUseCase(
        sources, registry, new DeterministicContentNormalizer(), rawItems, activity, clock);
  }

  @Test
  void collectingTheSameItemTwicePersistsExactlyOneRawInformationItem() {
    Source source =
        sources.save(
            new Source(
                null,
                "http",
                "Feed",
                "https://ex.test/rss-" + UUID.randomUUID(),
                true,
                null,
                null));
    CollectedItem item =
        new CollectedItem(
            "ext-42",
            "https://ex.test/article",
            "Title",
            Instant.parse("2026-01-15T09:00:00Z"),
            "  Article body  \r\n\r\n",
            "text/html");
    DefaultCollectFromSourceUseCase useCase = useCaseReturning(item);

    CollectionReport first = useCase.collectFromSource(source.id());
    CollectionReport second = useCase.collectFromSource(source.id());

    assertThat(first.status()).isEqualTo(CollectionReport.Status.COLLECTED);
    assertThat(first.newItemCount()).isEqualTo(1);
    assertThat(second.status()).isEqualTo(CollectionReport.Status.COLLECTED);
    assertThat(second.newItemCount()).isZero();
    assertThat(second.duplicateCount()).isEqualTo(1);

    RawInformationItem persisted =
        rawItems.findByIdentity(source.id(), "ext-42", contentHashOf("Article body")).orElseThrow();
    assertThat(persisted.sourceId()).isEqualTo(source.id());
    assertThat(persisted.originalUrl()).isEqualTo("https://ex.test/article");
    assertThat(persisted.publishedAt()).isEqualTo(Instant.parse("2026-01-15T09:00:00Z"));
    assertThat(persisted.rawContent()).isEqualTo("  Article body  \r\n\r\n");
    assertThat(persisted.normalizedContent()).isEqualTo("Article body");
    assertThat(persisted.collectedAt()).isEqualTo(Instant.parse("2026-02-03T04:05:06Z"));
    assertThat(persisted.processingState().state()).isEqualTo(ProcessingState.NORMALIZED);
  }

  @Test
  void aNullSourceProvidedIdStillDeduplicatesOnSourceAndContentHash() {
    Source source =
        sources.save(
            new Source(
                null,
                "http",
                "Feed",
                "https://ex.test/rss-" + UUID.randomUUID(),
                true,
                null,
                null));
    CollectedItem item =
        new CollectedItem(null, "https://ex.test/x", null, null, "same content", "text/plain");
    DefaultCollectFromSourceUseCase useCase = useCaseReturning(item);

    useCase.collectFromSource(source.id());
    CollectionReport second = useCase.collectFromSource(source.id());

    assertThat(second.duplicateCount()).isEqualTo(1);
    assertThat(rawItems.findByIdentity(source.id(), null, contentHashOf("same content")))
        .isPresent();
  }

  private static String contentHashOf(String normalizedContent) {
    try {
      byte[] hash =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(normalizedContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(hash);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
