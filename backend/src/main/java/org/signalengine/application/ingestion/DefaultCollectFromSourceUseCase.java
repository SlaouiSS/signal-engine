package org.signalengine.application.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default implementation of {@link CollectFromSourceUseCase}. */
public final class DefaultCollectFromSourceUseCase implements CollectFromSourceUseCase {

  private static final Logger log = LoggerFactory.getLogger(DefaultCollectFromSourceUseCase.class);
  private static final String COLLECTION = "collection";

  private final SourceRepository sourceRepository;
  private final SourceCollectorRegistry sourceCollectorRegistry;
  private final ContentNormalizer contentNormalizer;
  private final RawInformationItemRepository rawInformationItemRepository;
  private final ActivityRecordRepository activityRecordRepository;
  private final Clock clock;

  public DefaultCollectFromSourceUseCase(
      SourceRepository sourceRepository,
      SourceCollectorRegistry sourceCollectorRegistry,
      ContentNormalizer contentNormalizer,
      RawInformationItemRepository rawInformationItemRepository,
      ActivityRecordRepository activityRecordRepository,
      Clock clock) {
    this.sourceRepository = sourceRepository;
    this.sourceCollectorRegistry = sourceCollectorRegistry;
    this.contentNormalizer = contentNormalizer;
    this.rawInformationItemRepository = rawInformationItemRepository;
    this.activityRecordRepository = activityRecordRepository;
    this.clock = clock;
  }

  @Override
  public CollectionReport collectFromSource(UUID sourceId) {
    Source source =
        sourceRepository
            .findById(sourceId)
            .orElseThrow(() -> new InvalidInputException("no source with id '" + sourceId + "'"));

    if (!source.enabled()) {
      return CollectionReport.skippedDisabled(sourceId);
    }

    SourceCollector collector = sourceCollectorRegistry.collectorFor(source).orElse(null);
    if (collector == null) {
      recordActivity(sourceId, "failure", "no collector for source type '" + source.type() + "'");
      return CollectionReport.noCollector(sourceId, source.type());
    }

    return switch (collector.collect(source)) {
      case CollectionFailed failed -> {
        recordActivity(sourceId, "failure", failed.reason());
        yield CollectionReport.failed(sourceId, failed.reason());
      }
      case Collected collected -> persistAll(source, collected.items());
    };
  }

  @Override
  public List<CollectionReport> collectFromEnabledSources() {
    return sourceRepository.findAll().stream()
        .filter(Source::enabled)
        .map(this::collectOneIsolatingFailure)
        .toList();
  }

  /**
   * Collects one source, turning an unexpected exception into a recorded failure so the batch
   * continues with the other sources (docs/08-ingestion.md Section 17).
   */
  private CollectionReport collectOneIsolatingFailure(Source source) {
    try {
      return collectFromSource(source.id());
    } catch (RuntimeException exception) {
      log.error("Unexpected failure collecting source {}", source.id(), exception);
      recordActivity(source.id(), "failure", "unexpected error: " + exception.getMessage());
      return CollectionReport.failed(source.id(), "unexpected error");
    }
  }

  private CollectionReport persistAll(Source source, List<CollectedItem> items) {
    int newItems = 0;
    int duplicates = 0;
    for (CollectedItem item : items) {
      if (item.rawContent() == null || item.rawContent().isBlank()) {
        continue;
      }
      if (persistIfNew(source, item)) {
        newItems++;
      } else {
        duplicates++;
      }
    }
    recordActivity(
        source.id(),
        "success",
        "collected %d item(s): %d new, %d already known"
            .formatted(newItems + duplicates, newItems, duplicates));
    return CollectionReport.collected(source.id(), newItems, duplicates);
  }

  /**
   * Returns true if a new Raw Information Item was persisted, false if it was an exact duplicate —
   * whether found by the up-front identity lookup or rejected by the database's identity constraint
   * because a concurrent collection persisted the same item first (docs/08-ingestion.md Section
   * 15).
   */
  private boolean persistIfNew(Source source, CollectedItem item) {
    String rawContent = item.rawContent();
    String normalizedContent = contentNormalizer.normalize(rawContent);
    String contentHash = sha256Hex(normalizedContent);

    if (rawInformationItemRepository
        .findByIdentity(source.id(), item.sourceProvidedId(), contentHash)
        .isPresent()) {
      return false;
    }

    Instant now = clock.instant();
    return rawInformationItemRepository.saveIfNew(
        new RawInformationItem(
            null,
            source.id(),
            item.sourceProvidedId(),
            contentHash,
            item.originalUrl(),
            rawContent,
            normalizedContent,
            null,
            item.publishedAt(),
            now,
            ProcessingState.normalized(now),
            null,
            null,
            null));
  }

  private void recordActivity(UUID sourceId, String outcome, String message) {
    activityRecordRepository.save(
        new ActivityRecord(null, clock.instant(), COLLECTION, outcome, message, sourceId, null));
  }

  private static String sha256Hex(String content) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is not available", impossible);
    }
  }
}
