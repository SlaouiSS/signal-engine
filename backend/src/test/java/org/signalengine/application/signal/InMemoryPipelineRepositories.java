package org.signalengine.application.signal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.application.persistence.SummaryRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.RelevantInformation;
import org.signalengine.domain.Signal;
import org.signalengine.domain.Summary;

/**
 * Minimal in-memory doubles for the pipeline's stateful repositories, so a unit test can drive the
 * multi-step {@code process()} loop (which re-reads state after every transition) without a
 * database.
 */
final class InMemoryPipelineRepositories {

  private InMemoryPipelineRepositories() {}

  static final class Raw implements RawInformationItemRepository {
    final Map<UUID, RawInformationItem> byId = new LinkedHashMap<>();

    RawInformationItem put(RawInformationItem item) {
      byId.put(item.id(), item);
      return item;
    }

    @Override
    public RawInformationItem save(RawInformationItem item) {
      RawInformationItem stored =
          item.id() == null
              ? new RawInformationItem(
                  UUID.randomUUID(),
                  item.sourceId(),
                  item.sourceProvidedId(),
                  item.contentHash(),
                  item.originalUrl(),
                  item.rawContent(),
                  item.normalizedContent(),
                  item.language(),
                  item.publishedAt(),
                  item.collectedAt(),
                  item.processingState(),
                  item.relevantInformationId(),
                  item.createdAt(),
                  item.updatedAt())
              : item;
      byId.put(stored.id(), stored);
      return stored;
    }

    @Override
    public boolean saveIfNew(RawInformationItem item) {
      boolean identityExists =
          byId.values().stream()
              .anyMatch(
                  existing ->
                      Objects.equals(existing.sourceId(), item.sourceId())
                          && Objects.equals(existing.sourceProvidedId(), item.sourceProvidedId())
                          && Objects.equals(existing.contentHash(), item.contentHash()));
      if (identityExists) {
        return false;
      }
      save(item);
      return true;
    }

    @Override
    public Optional<RawInformationItem> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<RawInformationItem> findByRelevantInformationId(UUID relevantInformationId) {
      return byId.values().stream()
          .filter(i -> relevantInformationId.equals(i.relevantInformationId()))
          .toList();
    }

    @Override
    public Optional<RawInformationItem> findByIdentity(
        UUID sourceId, String sourceProvidedId, String contentHash) {
      return Optional.empty();
    }

    @Override
    public List<RawInformationItem> findByProcessingState(String processingState, int limit) {
      return byId.values().stream()
          .filter(i -> processingState.equals(i.processingState().state()))
          .limit(limit)
          .toList();
    }

    @Override
    public List<RawInformationItem> findByProcessingStateIn(
        List<String> processingStates, int limit) {
      return byId.values().stream()
          .filter(i -> processingStates.contains(i.processingState().state()))
          .limit(limit)
          .toList();
    }

    @Override
    public List<RawInformationItem> findMostRecentlyGrouped(int limit) {
      return List.of();
    }

    @Override
    public boolean transitionFromState(
        UUID rawInformationItemId,
        String expectedState,
        ProcessingState newState,
        UUID relevantInformationId) {
      RawInformationItem current = byId.get(rawInformationItemId);
      if (current == null || !expectedState.equals(current.processingState().state())) {
        return false;
      }
      byId.put(
          rawInformationItemId,
          new RawInformationItem(
              current.id(),
              current.sourceId(),
              current.sourceProvidedId(),
              current.contentHash(),
              current.originalUrl(),
              current.rawContent(),
              current.normalizedContent(),
              current.language(),
              current.publishedAt(),
              current.collectedAt(),
              newState,
              relevantInformationId,
              current.createdAt(),
              Instant.now()));
      return true;
    }
  }

  static final class Relevant implements RelevantInformationRepository {
    final Map<UUID, RelevantInformation> byId = new LinkedHashMap<>();

    RelevantInformation put(RelevantInformation record) {
      byId.put(record.id(), record);
      return record;
    }

    @Override
    public RelevantInformation save(RelevantInformation record) {
      RelevantInformation stored =
          record.id() == null
              ? new RelevantInformation(
                  UUID.randomUUID(),
                  record.reason(),
                  record.matchedAreaCodes(),
                  record.matchedInterestIds(),
                  record.createdAt(),
                  record.updatedAt())
              : record;
      byId.put(stored.id(), stored);
      return stored;
    }

    @Override
    public Optional<RelevantInformation> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<RelevantInformation> findMostRecent(int maxResults) {
      return byId.values().stream()
          .sorted(Comparator.comparing(RelevantInformation::createdAt).reversed())
          .limit(maxResults)
          .toList();
    }
  }

  static final class Signals implements SignalRepository {
    final Map<UUID, Signal> byId = new LinkedHashMap<>();

    @Override
    public Signal save(Signal signal) {
      Signal stored =
          signal.id() == null
              ? new Signal(
                  UUID.randomUUID(),
                  signal.relevantInformationId(),
                  signal.state(),
                  Instant.now(),
                  Instant.now())
              : signal;
      byId.put(stored.id(), stored);
      return stored;
    }

    @Override
    public Optional<Signal> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Signal> findByRelevantInformationId(UUID relevantInformationId) {
      return byId.values().stream()
          .filter(s -> relevantInformationId.equals(s.relevantInformationId()))
          .findFirst();
    }

    @Override
    public List<Signal> findMostRecent(int maxResults) {
      return byId.values().stream()
          .sorted(Comparator.comparing(Signal::createdAt).reversed())
          .limit(maxResults)
          .toList();
    }
  }

  static final class Summaries implements SummaryRepository {
    final Map<UUID, Summary> byId = new LinkedHashMap<>();

    @Override
    public Summary save(Summary summary) {
      if (findBySignalId(summary.signalId()).isPresent()) {
        throw new IllegalStateException("summary already exists for signal " + summary.signalId());
      }
      Summary stored =
          summary.id() == null
              ? new Summary(
                  UUID.randomUUID(),
                  summary.signalId(),
                  summary.summaryText(),
                  summary.groundingNotes(),
                  Instant.now(),
                  Instant.now())
              : summary;
      byId.put(stored.id(), stored);
      return stored;
    }

    @Override
    public Optional<Summary> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Summary> findBySignalId(UUID signalId) {
      return byId.values().stream().filter(s -> signalId.equals(s.signalId())).findFirst();
    }
  }

  static final class ImmediateUnitOfWork implements UnitOfWork {
    @Override
    public <R> R inTransaction(Supplier<R> work) {
      return work.get();
    }
  }

  static List<Object> activityMessages(List<org.signalengine.domain.ActivityRecord> records) {
    List<Object> messages = new ArrayList<>();
    records.forEach(r -> messages.add(r.message()));
    return messages;
  }
}
