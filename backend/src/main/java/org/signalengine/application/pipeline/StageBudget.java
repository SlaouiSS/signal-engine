package org.signalengine.application.pipeline;

import java.time.Clock;
import java.time.Instant;

/**
 * Until when a pipeline stage may start new work (docs/adr/0019-pipeline-automation.md). It is an
 * admission limit: a unit of work already started finishes, bounded by the timeouts of its own
 * calls.
 */
public record StageBudget(Instant deadline, Clock clock) {

  private static final StageBudget UNLIMITED = new StageBudget(Instant.MAX, Clock.systemUTC());

  public static StageBudget unlimited() {
    return UNLIMITED;
  }

  /** No further unit of work may start: the time is spent, or the thread is being shut down. */
  public boolean exhausted() {
    return Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline);
  }
}
