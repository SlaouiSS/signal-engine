package org.signalengine.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiResponseMetadata;

/** Successive slow calls, as semantic chunking makes them, stop once the time budget is spent. */
class DeadlineAiCapabilityInvokerTest {

  private final MutableClock clock = new MutableClock();
  private final AiCallDeadline deadline = new AiCallDeadline(clock);
  private final AtomicInteger calls = new AtomicInteger();

  /** Each call takes ten minutes. */
  private final AiCapabilityInvoker slowProvider =
      new AiCapabilityInvoker() {
        @Override
        @SuppressWarnings("unchecked")
        public <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> type) {
          calls.incrementAndGet();
          clock.advance(Duration.ofMinutes(10));
          return new Produced<>(
              (R) "ok", new AiResponseMetadata("test", "test", "v1", 1L), request.correlationId());
        }
      };

  private final DeadlineAiCapabilityInvoker invoker =
      new DeadlineAiCapabilityInvoker(slowProvider, deadline);

  private AiCapabilityOutcome<String> call() {
    return invoker.invoke(AiCapabilityRequest.of("semantic-chunk-boundary", 1, "w"), String.class);
  }

  @Test
  void noCallStartsAfterTheBudgetSoItCanBeExceededByAtMostOneCall() {
    Instant start = clock.instant();

    AiCapabilityOutcome<String> last =
        deadline.within(
            Duration.ofMinutes(25),
            () -> {
              AiCapabilityOutcome<String> outcome = call();
              for (int window = 1; window < 100; window++) {
                outcome = call();
              }
              return outcome;
            });

    assertThat(calls).hasValue(3);
    assertThat(Duration.between(start, clock.instant())).isEqualTo(Duration.ofMinutes(30));
    assertThat(((Failed<String>) last).error().timeBudgetExceeded()).isTrue();
  }

  @Test
  void workThatSetNoBudgetIsUnaffected() {
    for (int i = 0; i < 5; i++) {
      assertThat(call()).isInstanceOf(Produced.class);
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-08-01T00:00:00Z"));

    void advance(Duration duration) {
      now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public Instant instant() {
      return now.get();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
