package org.signalengine.application.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.util.OptionalInt;
import java.util.UUID;
import org.signalengine.application.persistence.FailedAttemptKind;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.domain.ProcessingState;

/**
 * Decides what happens to an item whose stage failed but may succeed later: it waits for its next
 * attempt, or, once the limit is reached, moves to {@code failed} <em>retryable</em> — its data
 * kept, until an operator requeues it (docs/adr/0019-pipeline-automation.md).
 */
public final class ItemRetryScheduler {

  private final RawInformationItemRepository rawInformationItemRepository;
  private final UnitOfWork unitOfWork;
  private final RetryPolicy retryPolicy;
  private final Clock clock;

  public ItemRetryScheduler(
      RawInformationItemRepository rawInformationItemRepository,
      UnitOfWork unitOfWork,
      RetryPolicy retryPolicy,
      Clock clock) {
    this.rawInformationItemRepository = rawInformationItemRepository;
    this.unitOfWork = unitOfWork;
    this.retryPolicy = retryPolicy;
    this.clock = clock;
  }

  /**
   * Counts one failed attempt of {@code stage} against the item. Does nothing if the item is no
   * longer waiting for that stage.
   *
   * @return {@code true} if this failure exhausted the limit and the item is now set aside
   */
  public boolean recordFailedAttempt(
      UUID rawInformationItemId,
      UUID relevantInformationId,
      PipelineStage stage,
      FailedAttemptKind kind,
      String reason) {
    return unitOfWork.inTransaction(
        () -> {
          OptionalInt failures =
              rawInformationItemRepository.countFailedAttempt(
                  rawInformationItemId, stage.waitingState(), kind);
          if (failures.isEmpty()) {
            return false;
          }
          Instant now = clock.instant();
          if (failures.getAsInt() < retryPolicy.limitFor(kind)) {
            rawInformationItemRepository.postponeNextAttempt(
                rawInformationItemId,
                stage.waitingState(),
                now,
                now.plus(retryPolicy.delayAfter(failures.getAsInt())));
            return false;
          }
          return rawInformationItemRepository.transitionFromState(
              rawInformationItemId,
              stage.waitingState(),
              ProcessingState.failed(
                  stage.stageName(),
                  "set aside after " + failures.getAsInt() + " failed attempts: " + reason,
                  true,
                  now),
              relevantInformationId);
        });
  }
}
