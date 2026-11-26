package org.signalengine.infrastructure.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Suspends a capability whose provider configuration was refused, and resumes it under control
 * (docs/adr/0018-pipeline-reliability.md). Decorates the real {@link AiCapabilityInvoker}, so no
 * use case and no capability knows about suspension beyond the {@link
 * AiError#capabilitySuspended()} outcome it returns.
 *
 * <ul>
 *   <li>A call that comes back with a <em>configuration</em> refusal (Python's {@code
 *       providerFailureScope = configuration}: bad credentials, retired model) persists a
 *       suspension and returns a suspended failure.
 *   <li>A permanent rejection that <em>looks</em> specific to one request (HTTP 400, 413, 422…) is
 *       ambiguous: a parameter the provider does not accept is rejected for every request, and a
 *       context that is too long for one only. It is the request's own fault only if the capability
 *       is known to work, i.e. it succeeded within {@code evidenceWindow}; then the failure is
 *       returned as it is and the caller fails that item. Without that evidence the cause is
 *       uncertain: the capability is suspended and the item preserved, so a global mistake cannot
 *       turn a whole batch into permanent failures, and a genuinely oversized item is rejected for
 *       good once another item has shown that the capability works.
 *   <li>While suspended, the capability is not called: callers get the suspended failure at once.
 *   <li>When the cooldown ends, exactly one caller probes the capability. Success clears the
 *       suspension; a refused probe suspends again for a longer cooldown (exponential, capped); any
 *       other failure releases the probe so the next caller tries.
 * </ul>
 *
 * <p>The suspension is persisted, so it survives a restart: a restart does not retry. A suspension
 * never touches an item, so no item is lost; the pipeline simply stops early and resumes once the
 * capability answers again.
 */
public final class SuspendingAiCapabilityInvoker implements AiCapabilityInvoker {

  private static final Logger log = LoggerFactory.getLogger(SuspendingAiCapabilityInvoker.class);

  private final AiCapabilityInvoker delegate;
  private final AiCapabilitySuspensionStore store;
  private final AiCapabilitySuspensionProperties properties;
  private final Clock clock;

  SuspendingAiCapabilityInvoker(
      AiCapabilityInvoker delegate,
      AiCapabilitySuspensionStore store,
      AiCapabilitySuspensionProperties properties,
      Clock clock) {
    this.delegate = delegate;
    this.store = store;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> resultType) {
    String capability = request.capability();
    Instant now = clock.instant();
    Optional<AiCapabilitySuspension> existing = store.find(capability);

    boolean probing = false;
    if (existing.isPresent()) {
      boolean cooldownEnded = !existing.get().suspendedUntil().isAfter(now);
      if (!cooldownEnded || !store.claimProbe(capability, now, now.plus(properties.probeLease()))) {
        return suspendedFailure(request, store.find(capability).orElse(existing.get()), false);
      }
      probing = true;
    }

    final boolean probeInProgress = probing;
    AiCapabilityOutcome<R> outcome = delegate.invoke(request, resultType);
    return switch (outcome) {
      case Produced<R> produced -> {
        store.recordSuccess(capability, clock.instant());
        if (probeInProgress) {
          store.clear(capability);
          log.info("AI capability '{}' answered again; suspension cleared", capability);
        }
        yield produced;
      }
      case Failed<R> failed when failed.error().rejectsProviderConfiguration() ->
          suspendedFailure(
              request, suspend(capability, failed.error(), failed.error().message()), false);
      case Failed<R> failed when failed.error().contractMismatch() ->
          countRejection(request, failed, probeInProgress);
      case Failed<R> failed
          when failed.error().rejectsSingleRequest()
              && (probeInProgress || !knownToWork(capability)) ->
          suspendedFailure(
              request, suspend(capability, failed.error(), uncertainCause(failed)), true);
      case Failed<R> failed
          when failed.error().retryable() && !Thread.currentThread().isInterrupted() ->
          countTransientFailure(request, failed, probeInProgress);
      case Failed<R> failed -> {
        if (probeInProgress) {
          store.releaseProbe(capability, clock.instant());
        }
        yield failed;
      }
    };
  }

  /**
   * A request refused as invalid (or a contract violation) is ambiguous: it may be this input, or a
   * deployment where the two sides disagree. A first refusal is blamed on its input, which the
   * caller sets aside; a series of them with no success between is blamed on the deployment, and
   * suspends the capability so that no further item is set aside.
   */
  private <R> AiCapabilityOutcome<R> countRejection(
      AiCapabilityRequest request, Failed<R> failed, boolean probeInProgress) {
    String capability = request.capability();
    int consecutiveRejections = store.recordRejection(capability);
    if (!probeInProgress && consecutiveRejections < properties.transientFailureThreshold()) {
      return new Failed<>(failed.error().asRejectionOfThisInput());
    }
    String reason =
        "%d request(s) in a row refused as invalid, the last one: %s"
            .formatted(consecutiveRejections, failed.error().message());
    return suspendedFailure(request, suspend(capability, failed.error(), reason), true);
  }

  /**
   * The circuit breaker. A transient failure is returned as it is, until enough of them follow each
   * other with no success: then the capability is suspended, the caller's item is preserved, and no
   * further item attempt is used until a probe succeeds. A failed probe suspends again at once.
   *
   * <p>Interactive search and question answering share these capabilities, so a transient
   * suspension has its own short cooldown: it ends soon after the provider is back, while still
   * allowing at most one call per cooldown during an outage.
   */
  private <R> AiCapabilityOutcome<R> countTransientFailure(
      AiCapabilityRequest request, Failed<R> failed, boolean probeInProgress) {
    String capability = request.capability();
    int consecutiveFailures = store.recordTransientFailure(capability);
    if (!probeInProgress && consecutiveFailures < properties.transientFailureThreshold()) {
      return failed;
    }
    String reason =
        "%d transient failure(s) in a row, the last one: %s"
            .formatted(consecutiveFailures, failed.error().message());
    return suspendedFailure(
        request,
        suspend(
            capability,
            failed.error(),
            reason,
            properties.transientInitialCooldown(),
            properties.transientMaxCooldown()),
        true);
  }

  /** Whether the capability succeeded recently enough to prove that its configuration works. */
  private boolean knownToWork(String capability) {
    Instant earliestEvidence = clock.instant().minus(properties.evidenceWindow());
    return store.lastSuccess(capability).filter(earliestEvidence::isBefore).isPresent();
  }

  private String uncertainCause(Failed<?> failed) {
    return "the provider rejected a request ("
        + failed.error().message()
        + ") and the capability has not succeeded within "
        + properties.evidenceWindow()
        + ": suspected configuration problem";
  }

  /** Suspends with the long cooldown of a cause that someone has to fix. */
  private AiCapabilitySuspension suspend(String capability, AiError refusal, String reason) {
    return suspend(
        capability, refusal, reason, properties.initialCooldown(), properties.maxCooldown());
  }

  private AiCapabilitySuspension suspend(
      String capability,
      AiError refusal,
      String reason,
      Duration initialCooldown,
      Duration maxCooldown) {
    Integer providerStatus =
        refusal.details().get("providerStatus") instanceof Number status ? status.intValue() : null;
    AiCapabilitySuspension suspension =
        store.suspend(
            capability, clock.instant(), initialCooldown, maxCooldown, providerStatus, reason);
    log.warn(
        "AI capability '{}' suspended until {} (status {}, {} consecutive): {}",
        capability,
        suspension.suspendedUntil(),
        providerStatus,
        suspension.consecutiveFailures(),
        reason);
    return suspension;
  }

  private static <R> Failed<R> suspendedFailure(
      AiCapabilityRequest request, AiCapabilitySuspension suspension, boolean rejectedThisRequest) {
    Map<String, Object> details = new LinkedHashMap<>();
    if (rejectedThisRequest) {
      details.put(AiError.REJECTED_THIS_REQUEST_DETAIL, true);
    }
    details.put("suspendedUntil", suspension.suspendedUntil().toString());
    details.put("consecutiveFailures", suspension.consecutiveFailures());
    if (suspension.providerStatus() != null) {
      details.put("providerStatus", suspension.providerStatus());
    }
    String message =
        "capability '%s' is suspended until %s: %s"
            .formatted(request.capability(), suspension.suspendedUntil(), suspension.reason());
    return new Failed<>(
        new AiError(
            AiError.CAPABILITY_SUSPENDED_CODE,
            AiError.SUSPENDED_CATEGORY,
            true,
            message,
            request.correlationId(),
            details));
  }
}
