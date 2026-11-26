package org.signalengine.application.pipeline;

import java.time.Duration;
import java.util.function.DoubleSupplier;
import org.signalengine.application.persistence.FailedAttemptKind;

/**
 * How often a failed stage is attempted again, and how long it waits in between: bounded attempts
 * with exponential backoff and jitter (docs/03-technical-spec.md Section 13.3).
 *
 * @param maxTransientFailures attempts a stage gets against transient failures
 * @param maxUnexpectedFailures how often an unexpected error may repeat before the item is set
 *     aside, so a deterministic defect cannot be retried for ever
 * @param baseDelay the wait after the first failure
 * @param factor how much the wait grows with each further failure
 * @param maxDelay the cap of the wait
 * @param jitterRatio the largest share of the wait that jitter may remove
 * @param randomFraction a source of values in {@code [0, 1)} for the jitter
 */
public record RetryPolicy(
    int maxTransientFailures,
    int maxUnexpectedFailures,
    Duration baseDelay,
    double factor,
    Duration maxDelay,
    double jitterRatio,
    DoubleSupplier randomFraction) {

  public RetryPolicy {
    if (maxTransientFailures < 1 || maxUnexpectedFailures < 1) {
      throw new IllegalArgumentException("the retry limits must be at least 1");
    }
    if (factor < 1 || jitterRatio < 0 || jitterRatio >= 1) {
      throw new IllegalArgumentException("factor must be >= 1 and jitterRatio in [0, 1)");
    }
  }

  public int limitFor(FailedAttemptKind kind) {
    return kind == FailedAttemptKind.TRANSIENT ? maxTransientFailures : maxUnexpectedFailures;
  }

  /**
   * The wait after the {@code failures}th failure. Jitter only shortens it, so a wait equal to the
   * pipeline cadence is not pushed past the next cycle.
   */
  public Duration delayAfter(int failures) {
    double growth = Math.pow(factor, Math.max(0, failures - 1));
    double millis = Math.min(baseDelay.toMillis() * growth, maxDelay.toMillis());
    return Duration.ofMillis((long) (millis * (1 - jitterRatio * randomFraction.getAsDouble())));
  }
}
