package org.signalengine.infrastructure.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * A time budget for work that makes many AI calls on one thread, such as the semantic chunking of a
 * large content (docs/adr/0019-pipeline-automation.md). Once it has passed, no further call is
 * started on that thread; a call already in flight is bounded by its own timeout. Threads that did
 * not set a budget, such as request threads, are unaffected.
 *
 * <p>A thread-local because the calls are made deep inside a library that offers no way to pass a
 * deadline through.
 */
public final class AiCallDeadline {

  private final Clock clock;
  private final ThreadLocal<Instant> deadline = new ThreadLocal<>();

  public AiCallDeadline(Clock clock) {
    this.clock = clock;
  }

  /** Runs {@code work} with no AI call started after {@code budget} has elapsed. */
  public <R> R within(Duration budget, Supplier<R> work) {
    deadline.set(clock.instant().plus(budget));
    try {
      return work.get();
    } finally {
      deadline.remove();
    }
  }

  boolean passed() {
    Instant current = deadline.get();
    return current != null && !clock.instant().isBefore(current);
  }
}
