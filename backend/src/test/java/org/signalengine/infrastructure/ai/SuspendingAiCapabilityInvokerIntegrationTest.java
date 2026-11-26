package org.signalengine.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.ai.AiResponseMetadata;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A capability whose provider configuration was refused is suspended <em>durably</em> and resumed
 * under control, against a real PostgreSQL (docs/adr/0018-pipeline-reliability.md): a refusal is
 * told apart from a failure specific to one request, a suspension survives a restart, and when the
 * cooldown ends exactly one caller probes.
 */
class SuspendingAiCapabilityInvokerIntegrationTest extends AbstractPersistenceIntegrationTest {

  private static final Instant START = Instant.parse("2026-06-01T10:00:00Z");
  private static final AiCapabilitySuspensionProperties PROPERTIES =
      new AiCapabilitySuspensionProperties(
          Duration.ofMinutes(10),
          Duration.ofMinutes(40),
          Duration.ofMinutes(6),
          Duration.ofHours(24),
          3,
          Duration.ofSeconds(30),
          Duration.ofMinutes(5));

  @Autowired private JdbcClient jdbcClient;

  private final MutableClock clock = new MutableClock(START);
  private final String capability = "capability-" + UUID.randomUUID();
  private final ScriptedInvoker delegate = new ScriptedInvoker();

  private AiCapabilitySuspensionStore store() {
    return new AiCapabilitySuspensionStore(jdbcClient);
  }

  private SuspendingAiCapabilityInvoker invoker() {
    return new SuspendingAiCapabilityInvoker(delegate, store(), PROPERTIES, clock);
  }

  private AiCapabilityOutcome<String> call(AiCapabilityInvoker invoker) {
    return invoker.invoke(AiCapabilityRequest.of(capability, 1, "payload"), String.class);
  }

  private static AiError configurationRefusal() {
    return new AiError(
        "AI_PROVIDER_UNAVAILABLE",
        "provider",
        false,
        "NVIDIA Build returned HTTP 404",
        UUID.randomUUID(),
        Map.of("providerStatus", 404, "providerFailureScope", "configuration"));
  }

  private static AiError requestSpecificRejection() {
    return new AiError(
        "AI_PROVIDER_UNAVAILABLE",
        "provider",
        false,
        "NVIDIA Build returned HTTP 422",
        UUID.randomUUID(),
        Map.of("providerStatus", 422, "providerFailureScope", "request"));
  }

  private static AiError transientFailure() {
    return new AiError(
        "AI_PROVIDER_UNAVAILABLE",
        "provider",
        true,
        "NVIDIA Build returned HTTP 503",
        UUID.randomUUID(),
        Map.of("providerStatus", 503));
  }

  private static AiError error(AiCapabilityOutcome<String> outcome) {
    return ((Failed<String>) outcome).error();
  }

  @Test
  void aConfigurationRefusalSuspendsTheCapabilityAndStopsCallingIt() {
    delegate.willFail(configurationRefusal());
    SuspendingAiCapabilityInvoker invoker = invoker();

    AiCapabilityOutcome<String> first = call(invoker);
    AiCapabilityOutcome<String> second = call(invoker);

    assertThat(error(first).capabilitySuspended()).isTrue();
    assertThat(error(first).suspendedByRejectingThisRequest())
        .as("a refused configuration is nobody's fault in particular")
        .isFalse();
    assertThat(error(first).message()).contains("HTTP 404");
    assertThat(error(second).capabilitySuspended()).isTrue();
    assertThat(delegate.calls()).as("the suspended capability is not called again").isEqualTo(1);
    assertThat(store().find(capability))
        .hasValueSatisfying(
            suspension -> {
              assertThat(suspension.providerStatus()).isEqualTo(404);
              assertThat(suspension.suspendedUntil()).isEqualTo(START.plus(Duration.ofMinutes(10)));
            });
  }

  @Test
  void aRejectionOfOneRequestIsItsOwnFaultOnceTheCapabilityIsKnownToWork() {
    SuspendingAiCapabilityInvoker invoker = invoker();
    delegate.willSucceed("works");
    call(invoker);
    delegate.willFail(requestSpecificRejection());

    AiCapabilityOutcome<String> first = call(invoker);
    call(invoker);

    assertThat(error(first).capabilitySuspended()).isFalse();
    assertThat(error(first).message()).contains("HTTP 422");
    assertThat(delegate.calls()).as("one success, then two rejected requests").isEqualTo(3);
    assertThat(store().find(capability)).isEmpty();
  }

  @Test
  void aRejectionOfOneRequestIsUncertainWhenTheCapabilityNeverSucceededAndSuspendsIt() {
    delegate.willFail(requestSpecificRejection());
    SuspendingAiCapabilityInvoker invoker = invoker();

    AiCapabilityOutcome<String> first = call(invoker);
    AiCapabilityOutcome<String> second = call(invoker);

    assertThat(error(first).capabilitySuspended())
        .as("not blamed on the request: the item is preserved")
        .isTrue();
    assertThat(error(first).message()).contains("suspected configuration problem");
    assertThat(error(first).suspendedByRejectingThisRequest())
        .as("this request may be the bad one")
        .isTrue();
    assertThat(error(second).capabilitySuspended()).isTrue();
    assertThat(error(second).suspendedByRejectingThisRequest())
        .as("merely skipped: not suspect")
        .isFalse();
    assertThat(delegate.calls()).as("no further call while suspended").isEqualTo(1);
    assertThat(store().find(capability)).isPresent();
  }

  @Test
  void aSuccessOlderThanTheEvidenceWindowDoesNotProveTheCapabilityStillWorks() {
    SuspendingAiCapabilityInvoker invoker = invoker();
    delegate.willSucceed("worked once");
    call(invoker);
    clock.advance(PROPERTIES.evidenceWindow().plusMinutes(1));
    delegate.willFail(requestSpecificRejection());

    AiCapabilityOutcome<String> outcome = call(invoker);

    assertThat(error(outcome).capabilitySuspended()).isTrue();
  }

  @Test
  void aProbeThatIsRejectedAsARequestDoesNotProveTheCapabilityWorksAndStaysSuspended() {
    delegate.willFail(configurationRefusal());
    call(invoker());
    clock.advance(Duration.ofMinutes(10));
    delegate.willFail(requestSpecificRejection());

    AiCapabilityOutcome<String> probe = call(invoker());

    assertThat(error(probe).capabilitySuspended()).isTrue();
    assertThat(store().find(capability))
        .hasValueSatisfying(
            suspension -> assertThat(suspension.consecutiveFailures()).isEqualTo(2));
  }

  @Test
  void aSuccessIsRecordedAsEvidenceThatTheCapabilityWorks() {
    delegate.willSucceed("works");

    call(invoker());

    assertThat(store().lastSuccess(capability)).contains(START);
  }

  @Test
  void aTransientFailureDoesNotSuspendTheCapability() {
    delegate.willFail(transientFailure());
    SuspendingAiCapabilityInvoker invoker = invoker();

    call(invoker);
    call(invoker);

    assertThat(delegate.calls()).isEqualTo(2);
    assertThat(store().find(capability)).isEmpty();
  }

  @Test
  void theSuspensionSurvivesARestart() {
    delegate.willFail(configurationRefusal());
    call(invoker());

    SuspendingAiCapabilityInvoker afterRestart = invoker();
    AiCapabilityOutcome<String> outcome = call(afterRestart);

    assertThat(error(outcome).capabilitySuspended()).isTrue();
    assertThat(delegate.calls()).as("a restart does not retry").isEqualTo(1);
  }

  @Test
  void aSuccessfulProbeAfterTheCooldownClearsTheSuspension() {
    delegate.willFail(configurationRefusal());
    call(invoker());
    clock.advance(Duration.ofMinutes(10));
    delegate.willSucceed("recovered");

    AiCapabilityOutcome<String> outcome = call(invoker());

    assertThat(outcome).isInstanceOf(Produced.class);
    assertThat(store().find(capability)).isEmpty();
    assertThat(delegate.calls()).isEqualTo(2);
  }

  @Test
  void aRefusedProbeSuspendsAgainForALongerCooldown() {
    delegate.willFail(configurationRefusal());
    call(invoker());
    clock.advance(Duration.ofMinutes(10));

    AiCapabilityOutcome<String> probe = call(invoker());

    assertThat(error(probe).capabilitySuspended()).isTrue();
    assertThat(store().find(capability))
        .hasValueSatisfying(
            suspension -> {
              assertThat(suspension.consecutiveFailures()).isEqualTo(2);
              assertThat(suspension.suspendedUntil())
                  .isEqualTo(clock.instant().plus(Duration.ofMinutes(20)));
            });
  }

  @Test
  void theCooldownIsCappedAtTheConfiguredMaximum() {
    delegate.willFail(configurationRefusal());
    call(invoker());
    for (int refusedProbe = 0; refusedProbe < 5; refusedProbe++) {
      clock.advance(Duration.ofHours(2));
      call(invoker());
    }

    assertThat(store().find(capability))
        .hasValueSatisfying(
            suspension ->
                assertThat(suspension.suspendedUntil())
                    .isEqualTo(clock.instant().plus(PROPERTIES.maxCooldown())));
  }

  @Test
  void aProbeThatFailsTransientlySuspendsAgainSoThatNoItemAttemptIsUsed() {
    delegate.willFail(configurationRefusal());
    call(invoker());
    clock.advance(Duration.ofMinutes(10));
    delegate.willFail(transientFailure());

    AiCapabilityOutcome<String> failedProbe = call(invoker());
    AiCapabilityOutcome<String> immediatelyAfter = call(invoker());

    assertThat(error(failedProbe).capabilitySuspended()).isTrue();
    assertThat(error(immediatelyAfter).capabilitySuspended()).isTrue();
    assertThat(delegate.calls()).as("one refusal, then one probe").isEqualTo(2);
  }

  @Test
  void aProbeWhoseCallerNeverReportedIsTakenOverOnceItsLeaseExpires() {
    delegate.willFail(configurationRefusal());
    call(invoker());
    clock.advance(Duration.ofMinutes(10));
    // A caller claimed the probe and died before reporting.
    assertThat(
            store()
                .claimProbe(
                    capability, clock.instant(), clock.instant().plus(PROPERTIES.probeLease())))
        .isTrue();
    delegate.willSucceed("recovered");

    assertThat(error(call(invoker())).capabilitySuspended()).isTrue();
    clock.advance(PROPERTIES.probeLease());

    assertThat(call(invoker())).isInstanceOf(Produced.class);
    assertThat(store().find(capability)).isEmpty();
  }

  @Test
  void aProlongedOutageLetsAtMostTwoFailuresReachTheCallers() {
    delegate.willFail(transientFailure());
    int failuresChargedToCallers = 0;

    // 24 hours of outage, one call a minute.
    for (int minute = 0; minute < 24 * 60; minute++) {
      if (!error(call(invoker())).capabilitySuspended()) {
        failuresChargedToCallers++;
      }
      clock.advance(Duration.ofMinutes(1));
    }

    assertThat(failuresChargedToCallers).isEqualTo(2);
    assertThat(delegate.calls())
        .as("three calls open the circuit, then one probe per cooldown of at most 5 minutes")
        .isLessThanOrEqualTo(3 + 24 * 60 / 5 + 4);
  }

  @Test
  void aTransientSuspensionEndsQuicklyOnceTheProviderIsBack() {
    delegate.willFail(transientFailure());
    for (int minute = 0; minute < 120; minute++) {
      call(invoker());
      clock.advance(Duration.ofMinutes(1));
    }
    delegate.willSucceed("recovered");
    clock.advance(PROPERTIES.transientMaxCooldown());

    assertThat(call(invoker())).isInstanceOf(Produced.class);
  }

  @Test
  void theFirstTransientSuspensionLastsThirtySeconds() {
    delegate.willFail(transientFailure());
    SuspendingAiCapabilityInvoker invoker = invoker();
    call(invoker);
    call(invoker);
    call(invoker);
    delegate.willSucceed("recovered");

    clock.advance(Duration.ofSeconds(29));
    assertThat(error(call(invoker)).capabilitySuspended()).isTrue();
    clock.advance(Duration.ofSeconds(1));

    assertThat(call(invoker)).isInstanceOf(Produced.class);
  }

  @Test
  void aRefusedConfigurationKeepsItsLongCooldown() {
    delegate.willFail(configurationRefusal());
    call(invoker());
    delegate.willSucceed("fixed");
    clock.advance(PROPERTIES.transientMaxCooldown());

    assertThat(error(call(invoker())).capabilitySuspended()).isTrue();
  }

  private static AiError invalidRequest() {
    return new AiError(
        "AI_REQUEST_INVALID", "request", false, "payload is invalid", UUID.randomUUID(), Map.of());
  }

  @Test
  void aSingleInvalidRequestIsBlamedOnItsInputAndLeavesTheCapabilityAvailable() {
    delegate.willFail(invalidRequest());

    AiCapabilityOutcome<String> outcome = call(invoker());

    assertThat(error(outcome).rejectsThisInput()).isTrue();
    assertThat(error(outcome).capabilitySuspended()).isFalse();
    assertThat(store().find(capability)).isEmpty();
  }

  @Test
  void invalidRequestsSeparatedBySuccessesNeverSuspend() {
    SuspendingAiCapabilityInvoker invoker = invoker();
    for (int round = 0; round < 5; round++) {
      delegate.willFail(invalidRequest());
      call(invoker);
      delegate.willSucceed("works");
      call(invoker);
    }

    assertThat(store().find(capability)).isEmpty();
  }

  @Test
  void aSuccessBetweenTransientFailuresResetsTheirCount() {
    SuspendingAiCapabilityInvoker invoker = invoker();
    for (int round = 0; round < 3; round++) {
      delegate.willFail(transientFailure());
      call(invoker);
      call(invoker);
      delegate.willSucceed("works");
      call(invoker);
    }

    assertThat(store().find(capability)).isEmpty();
  }

  @Test
  void aSeriesOfContractMismatchesSuspendsTheCapabilityEvenWhenItWasKnownToWork() {
    delegate.willSucceed("works");
    call(invoker());
    delegate.willFail(
        new AiError(
            "AI_CONTRACT_VIOLATION",
            "contract",
            false,
            "the AI result does not match",
            UUID.randomUUID(),
            Map.of()));

    AiCapabilityOutcome<String> first = call(invoker());
    AiCapabilityOutcome<String> second = call(invoker());
    AiCapabilityOutcome<String> third = call(invoker());
    call(invoker());

    assertThat(error(first).rejectsThisInput()).isTrue();
    assertThat(error(second).rejectsThisInput()).isTrue();
    assertThat(error(third).capabilitySuspended()).isTrue();
    assertThat(delegate.calls()).as("one success, three refusals, then nothing").isEqualTo(4);
  }

  @Test
  void afterTheCooldownExactlyOneOfManyConcurrentCallersProbes() throws Exception {
    delegate.willFail(configurationRefusal());
    call(invoker());
    clock.advance(Duration.ofMinutes(10));
    delegate.willSucceedSlowly("recovered", Duration.ofMillis(400));

    int callers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    try {
      CountDownLatch startTogether = new CountDownLatch(1);
      List<Future<AiCapabilityOutcome<String>>> outcomes = new ArrayList<>();
      for (int caller = 0; caller < callers; caller++) {
        Callable<AiCapabilityOutcome<String>> work =
            () -> {
              startTogether.await();
              return call(invoker());
            };
        outcomes.add(pool.submit(work));
      }
      startTogether.countDown();

      long probes = 0;
      long suspended = 0;
      for (Future<AiCapabilityOutcome<String>> outcome : outcomes) {
        AiCapabilityOutcome<String> result = outcome.get(20, TimeUnit.SECONDS);
        if (result instanceof Produced) {
          probes++;
        } else if (error(result).capabilitySuspended()) {
          suspended++;
        }
      }

      assertThat(probes).as("exactly one caller probed").isEqualTo(1);
      assertThat(suspended).isEqualTo(callers - 1);
      assertThat(delegate.calls()).as("one refusal, then one probe").isEqualTo(2);
    } finally {
      pool.shutdownNow();
    }
  }

  // --- test doubles ------------------------------------------------------

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
      this.now = new AtomicReference<>(start);
    }

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

  /** A delegate that answers with whatever it was last told to, and counts its calls. */
  private static final class ScriptedInvoker implements AiCapabilityInvoker {
    private final AtomicInteger calls = new AtomicInteger();
    private volatile AiError failure;
    private volatile String success;
    private volatile Duration delay = Duration.ZERO;

    void willFail(AiError error) {
      this.failure = error;
      this.success = null;
      this.delay = Duration.ZERO;
    }

    void willSucceed(String value) {
      willSucceedSlowly(value, Duration.ZERO);
    }

    void willSucceedSlowly(String value, Duration answerAfter) {
      this.failure = null;
      this.success = value;
      this.delay = answerAfter;
    }

    int calls() {
      return calls.get();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> resultType) {
      calls.incrementAndGet();
      if (!delay.isZero()) {
        try {
          Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      if (failure != null) {
        return new Failed<>(failure);
      }
      return new Produced<>(
          (R) success,
          new AiResponseMetadata("test", "test", "test/v1", 1L),
          request.correlationId());
    }
  }
}
