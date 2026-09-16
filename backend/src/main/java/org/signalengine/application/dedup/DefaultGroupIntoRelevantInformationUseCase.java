package org.signalengine.application.dedup;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.dedup.NearDuplicateAssessor.Candidate;
import org.signalengine.application.dedup.NearDuplicateAssessor.NearDuplicateAssessment.Assessed;
import org.signalengine.application.dedup.NearDuplicateAssessor.NearDuplicateAssessment.AssessmentUnavailable;
import org.signalengine.application.dedup.NearDuplicateAssessor.Query;
import org.signalengine.application.dedup.NearDuplicateAssessor.Verdict;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.domain.ActivityRecord;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.RelevantInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link GroupIntoRelevantInformationUseCase}
 * (docs/adr/0007-semantic-deduplication-relevant-information.md).
 *
 * <p>Per item: skip unless it is {@code normalized}; select a bounded, recency-ordered candidate
 * pool of already-grouped items; ask {@link NearDuplicateAssessor}; then Java decides — associate
 * with the first candidate the AI marked as the same story, or create a new Relevant Information
 * record. The processing-state transition is conditional on the item still being {@code
 * normalized}, so a concurrent second run cannot double-group it.
 */
public final class DefaultGroupIntoRelevantInformationUseCase
    implements GroupIntoRelevantInformationUseCase {

  private static final Logger log =
      LoggerFactory.getLogger(DefaultGroupIntoRelevantInformationUseCase.class);
  private static final String STAGE = "near_duplicate_assessment";

  private final RawInformationItemRepository rawInformationItemRepository;
  private final RelevantInformationRepository relevantInformationRepository;
  private final NearDuplicateAssessor nearDuplicateAssessor;
  private final ActivityRecordRepository activityRecordRepository;
  private final UnitOfWork unitOfWork;
  private final Clock clock;
  private final int candidatePoolSize;
  private final int batchLimit;

  public DefaultGroupIntoRelevantInformationUseCase(
      RawInformationItemRepository rawInformationItemRepository,
      RelevantInformationRepository relevantInformationRepository,
      NearDuplicateAssessor nearDuplicateAssessor,
      ActivityRecordRepository activityRecordRepository,
      UnitOfWork unitOfWork,
      Clock clock,
      int candidatePoolSize,
      int batchLimit) {
    this.rawInformationItemRepository = rawInformationItemRepository;
    this.relevantInformationRepository = relevantInformationRepository;
    this.nearDuplicateAssessor = nearDuplicateAssessor;
    this.activityRecordRepository = activityRecordRepository;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
    this.candidatePoolSize = candidatePoolSize;
    this.batchLimit = batchLimit;
  }

  @Override
  public GroupingReport groupRawInformationItem(UUID rawInformationItemId) {
    RawInformationItem item =
        rawInformationItemRepository
            .findById(rawInformationItemId)
            .orElseThrow(
                () ->
                    new InvalidInputException(
                        "no raw information item with id '" + rawInformationItemId + "'"));

    if (!ProcessingState.NORMALIZED.equals(item.processingState().state())) {
      return GroupingReport.skippedAlreadyAssessed(
          rawInformationItemId, item.processingState().state());
    }

    if (isBlank(item.normalizedContent())) {
      return createNewRelevantInformation(item);
    }

    List<CandidateInformation> candidatePool = selectCandidatePool(item.id());
    if (candidatePool.isEmpty()) {
      return createNewRelevantInformation(item);
    }

    List<Candidate> candidates =
        candidatePool.stream().map(c -> new Candidate(c.rawItemId(), c.text())).toList();
    Map<UUID, UUID> relevantInformationByCandidate =
        candidatePool.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CandidateInformation::rawItemId,
                    CandidateInformation::relevantInformationId,
                    (first, ignored) -> first,
                    LinkedHashMap::new));

    return switch (nearDuplicateAssessor.assess(new Query(item.normalizedContent(), candidates))) {
      case AssessmentUnavailable unavailable -> handleFailure(item, unavailable);
      case Assessed assessed ->
          decideGrouping(item, assessed.verdicts(), relevantInformationByCandidate);
    };
  }

  @Override
  public List<GroupingReport> groupNormalizedRawInformationItems() {
    return rawInformationItemRepository
        .findByProcessingState(ProcessingState.NORMALIZED, batchLimit)
        .stream()
        .map(item -> groupOneIsolatingFailure(item.id()))
        .toList();
  }

  private GroupingReport groupOneIsolatingFailure(UUID rawInformationItemId) {
    try {
      return groupRawInformationItem(rawInformationItemId);
    } catch (RuntimeException failure) {
      log.error(
          "Unexpected failure grouping raw information item {}", rawInformationItemId, failure);
      recordActivity(rawInformationItemId, "failure", "unexpected error: " + failure.getMessage());
      return GroupingReport.assessmentFailed(rawInformationItemId, "unexpected error");
    }
  }

  private GroupingReport decideGrouping(
      RawInformationItem item,
      List<Verdict> verdicts,
      Map<UUID, UUID> relevantInformationByCandidate) {
    Optional<Verdict> match = verdicts.stream().filter(Verdict::sameUnderlyingStory).findFirst();
    if (match.isEmpty()) {
      return createNewRelevantInformation(item);
    }
    UUID targetRelevantInformationId =
        relevantInformationByCandidate.get(match.get().rawInformationItemId());
    return associateWithExisting(item, targetRelevantInformationId, match.get().reason());
  }

  private GroupingReport associateWithExisting(
      RawInformationItem item, UUID relevantInformationId, String reason) {
    return unitOfWork.inTransaction(
        () -> {
          boolean moved =
              rawInformationItemRepository.transitionFromState(
                  item.id(),
                  ProcessingState.NORMALIZED,
                  ProcessingState.duplicate(clock.instant()),
                  relevantInformationId);
          if (!moved) {
            return skippedBecauseAnotherRunHandledIt(item.id());
          }
          recordActivity(
              item.id(),
              "success",
              "near-duplicate of relevant information " + relevantInformationId + "; " + reason);
          return GroupingReport.associatedWithExisting(item.id(), relevantInformationId, reason);
        });
  }

  private GroupingReport createNewRelevantInformation(RawInformationItem item) {
    try {
      return unitOfWork.inTransaction(
          () -> {
            RelevantInformation created =
                relevantInformationRepository.save(
                    new RelevantInformation(null, null, Set.of(), Set.of(), null, null));
            boolean moved =
                rawInformationItemRepository.transitionFromState(
                    item.id(),
                    ProcessingState.NORMALIZED,
                    ProcessingState.deduplicated(clock.instant()),
                    created.id());
            if (!moved) {
              throw new ConcurrentGroupingException(item.id());
            }
            recordActivity(
                item.id(),
                "success",
                "distinct information; created relevant information " + created.id());
            return GroupingReport.createdNew(item.id(), created.id());
          });
    } catch (ConcurrentGroupingException lostRace) {
      return skippedBecauseAnotherRunHandledIt(item.id());
    }
  }

  private GroupingReport handleFailure(RawInformationItem item, AssessmentUnavailable unavailable) {
    var error = unavailable.error();
    recordActivity(
        item.id(),
        "failure",
        "near-duplicate assessment failed (" + error.code() + "): " + error.message());
    if (error.retryable()) {
      return GroupingReport.assessmentFailed(item.id(), "retryable: " + error.code());
    }
    unitOfWork.inTransaction(
        () -> {
          rawInformationItemRepository.transitionFromState(
              item.id(),
              ProcessingState.NORMALIZED,
              ProcessingState.failed(STAGE, error.message(), false, clock.instant()),
              null);
          return null;
        });
    return GroupingReport.assessmentFailed(item.id(), "non-retryable: " + error.code());
  }

  private GroupingReport skippedBecauseAnotherRunHandledIt(UUID rawInformationItemId) {
    String currentState =
        rawInformationItemRepository
            .findById(rawInformationItemId)
            .map(item -> item.processingState().state())
            .orElse("unknown");
    return GroupingReport.skippedAlreadyAssessed(rawInformationItemId, currentState);
  }

  private List<CandidateInformation> selectCandidatePool(UUID itemBeingAssessed) {
    return rawInformationItemRepository.findMostRecentlyGrouped(candidatePoolSize).stream()
        .filter(candidate -> !candidate.id().equals(itemBeingAssessed))
        .filter(candidate -> candidate.relevantInformationId() != null)
        .filter(candidate -> !isBlank(candidate.normalizedContent()))
        .map(
            candidate ->
                new CandidateInformation(
                    candidate.id(),
                    candidate.relevantInformationId(),
                    candidate.normalizedContent()))
        .toList();
  }

  private void recordActivity(UUID rawInformationItemId, String outcome, String message) {
    activityRecordRepository.save(
        new ActivityRecord(
            null, clock.instant(), STAGE, outcome, message, null, rawInformationItemId));
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record CandidateInformation(UUID rawItemId, UUID relevantInformationId, String text) {}

  /** Internal: signals that a concurrent run grouped the item first, so roll back this attempt. */
  private static final class ConcurrentGroupingException extends RuntimeException {
    ConcurrentGroupingException(UUID rawInformationItemId) {
      super("raw information item " + rawInformationItemId + " was grouped concurrently");
    }
  }
}
