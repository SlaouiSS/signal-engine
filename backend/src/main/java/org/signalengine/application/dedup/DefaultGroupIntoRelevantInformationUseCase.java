package org.signalengine.application.dedup;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.ai.AiInputLimits;
import org.signalengine.application.dedup.NearDuplicateAssessor.Candidate;
import org.signalengine.application.dedup.NearDuplicateAssessor.NearDuplicateAssessment.Assessed;
import org.signalengine.application.dedup.NearDuplicateAssessor.NearDuplicateAssessment.AssessmentUnavailable;
import org.signalengine.application.dedup.NearDuplicateAssessor.Query;
import org.signalengine.application.dedup.NearDuplicateAssessor.Verdict;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.FailedAttemptKind;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.application.pipeline.ItemRetryScheduler;
import org.signalengine.application.pipeline.PipelineStage;
import org.signalengine.application.pipeline.StageBudget;
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
  private static final String STAGE = PipelineStage.NEAR_DUPLICATE_ASSESSMENT.stageName();

  private final RawInformationItemRepository rawInformationItemRepository;
  private final RelevantInformationRepository relevantInformationRepository;
  private final NearDuplicateAssessor nearDuplicateAssessor;
  private final ActivityRecordRepository activityRecordRepository;
  private final UnitOfWork unitOfWork;
  private final ItemRetryScheduler itemRetryScheduler;
  private final Clock clock;
  private final int candidatePoolSize;
  private final int batchLimit;

  public DefaultGroupIntoRelevantInformationUseCase(
      RawInformationItemRepository rawInformationItemRepository,
      RelevantInformationRepository relevantInformationRepository,
      NearDuplicateAssessor nearDuplicateAssessor,
      ActivityRecordRepository activityRecordRepository,
      UnitOfWork unitOfWork,
      ItemRetryScheduler itemRetryScheduler,
      Clock clock,
      int candidatePoolSize,
      int batchLimit) {
    this.rawInformationItemRepository = rawInformationItemRepository;
    this.relevantInformationRepository = relevantInformationRepository;
    this.nearDuplicateAssessor = nearDuplicateAssessor;
    this.activityRecordRepository = activityRecordRepository;
    this.unitOfWork = unitOfWork;
    this.itemRetryScheduler = itemRetryScheduler;
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

  /**
   * Groups one bounded page of normalized items. An item that needs the suspended near-duplicate
   * capability is left untouched (and moved behind the others); it costs one lookup, not a call, so
   * the sweep carries on — an item with no candidate to compare against needs no assessment at all
   * and still progresses (docs/adr/0018-pipeline-reliability.md). One activity record summarises
   * the suspension.
   */
  @Override
  public List<GroupingReport> groupNormalizedRawInformationItems(StageBudget budget) {
    List<GroupingReport> reports = new ArrayList<>();
    for (RawInformationItem item :
        rawInformationItemRepository.findDueByProcessingState(
            ProcessingState.NORMALIZED, clock.instant(), batchLimit)) {
      if (budget.exhausted()) {
        break;
      }
      reports.add(groupOneIsolatingFailure(item.id()));
    }
    List<GroupingReport> suspended =
        reports.stream()
            .filter(report -> report.outcome() == GroupingReport.Outcome.SUSPENDED)
            .toList();
    if (!suspended.isEmpty()) {
      recordActivity(
          null,
          "failure",
          suspended.size()
              + " item(s) wait for a suspended near-duplicate capability: "
              + suspended.get(0).detail());
    }
    return List.copyOf(reports);
  }

  private GroupingReport groupOneIsolatingFailure(UUID rawInformationItemId) {
    try {
      return groupRawInformationItem(rawInformationItemId);
    } catch (RuntimeException failure) {
      log.error(
          "Unexpected failure grouping raw information item {}", rawInformationItemId, failure);
      recordActivity(rawInformationItemId, "failure", "unexpected error: " + failure.getMessage());
      countUnexpectedFailure(rawInformationItemId, failure);
      return GroupingReport.assessmentFailed(rawInformationItemId, "unexpected error");
    }
  }

  /**
   * An unexpected error uses no retry budget, but it is counted, so a deterministic defect cannot
   * be retried for ever. If even the counting fails (the database is away), the next run finds the
   * item as it was.
   */
  private void countUnexpectedFailure(UUID rawInformationItemId, RuntimeException failure) {
    if (Thread.currentThread().isInterrupted()) {
      return;
    }
    try {
      itemRetryScheduler.recordFailedAttempt(
          rawInformationItemId,
          null,
          PipelineStage.NEAR_DUPLICATE_ASSESSMENT,
          FailedAttemptKind.UNEXPECTED,
          "unexpected error: " + failure.getMessage());
    } catch (RuntimeException countingFailure) {
      log.warn(
          "Could not count the failure of raw information item {}",
          rawInformationItemId,
          countingFailure);
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
    if (error.capabilitySuspended()) {
      if (error.suspendedByRejectingThisRequest()) {
        // This item may be the bad one: behind the others, so a different item probes next time.
        rawInformationItemRepository.recordRetryableAttempt(
            item.id(), ProcessingState.NORMALIZED, clock.instant());
      }
      return GroupingReport.suspended(item.id(), error.message());
    }
    recordActivity(
        item.id(),
        "failure",
        "near-duplicate assessment failed (" + error.code() + "): " + error.message());
    if (error.rejectsThisInput()) {
      // Set aside, not failed for good: the content is kept and can be requeued.
      return fail(
          item,
          "set aside, the content cannot be processed as it is: " + error.message(),
          true,
          error);
    }
    if (error.retryable()) {
      if (!Thread.currentThread().isInterrupted()) {
        itemRetryScheduler.recordFailedAttempt(
            item.id(),
            null,
            PipelineStage.NEAR_DUPLICATE_ASSESSMENT,
            FailedAttemptKind.TRANSIENT,
            error.code() + ": " + error.message());
      }
      return GroupingReport.assessmentFailed(item.id(), "retryable: " + error.code());
    }
    return fail(item, error.message(), false, error);
  }

  private GroupingReport fail(
      RawInformationItem item, String reason, boolean requeueable, AiError error) {
    unitOfWork.inTransaction(
        () -> {
          rawInformationItemRepository.transitionFromState(
              item.id(),
              ProcessingState.NORMALIZED,
              ProcessingState.failed(STAGE, reason, requeueable, clock.instant()),
              null);
          return null;
        });
    return GroupingReport.assessmentFailed(
        item.id(), (requeueable ? "set aside: " : "non-retryable: ") + error.code());
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
        .filter(this::fitsTheComparisonLimit)
        .map(
            candidate ->
                new CandidateInformation(
                    candidate.id(),
                    candidate.relevantInformationId(),
                    candidate.normalizedContent()))
        .toList();
  }

  /**
   * An already grouped item too long to be compared cannot be a candidate: sending it would get the
   * whole comparison refused for the item being assessed.
   */
  private boolean fitsTheComparisonLimit(RawInformationItem candidate) {
    boolean fits =
        !AiInputLimits.exceeds(candidate.normalizedContent(), AiInputLimits.NEAR_DUPLICATE_TEXT);
    if (!fits) {
      log.warn(
          "Raw information item {} is too long to be compared; left out of the candidate pool",
          candidate.id());
    }
    return fits;
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
