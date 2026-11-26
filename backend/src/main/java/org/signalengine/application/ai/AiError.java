package org.signalengine.application.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A typed AI capability failure (docs/03-technical-spec.md Section 13.5). Either returned by the
 * Python service or synthesised by the Java adapter for a transport/contract/suspension failure.
 *
 * <ul>
 *   <li>{@code code} — a stable machine-readable string (e.g. {@code AI_OUTPUT_INVALID}); unknown
 *       codes are tolerated.
 *   <li>{@code category} — one of {@code request}, {@code ai_output}, {@code provider}, {@code
 *       timeout}, {@code internal} (from Python) or {@code transport}, {@code contract}, {@code
 *       suspended} (synthesised by Java). Kept as text so a new Python category never breaks
 *       deserialisation.
 *   <li>{@code retryable} — drives the caller's retry-vs-fail decision (docs/03-technical-spec.md
 *       Section 8.5, 13.2). Transport, timeout and transient provider failures are retryable;
 *       request, output-validation and contract violations are not.
 *   <li>{@code details} — optional, free-form diagnostic context; never contains secrets.
 * </ul>
 *
 * <p>A <em>suspended</em> capability (docs/adr/0018-pipeline-reliability.md) is one whose provider
 * configuration was refused (bad credentials, retired model). The call was not made; it is
 * retryable in the sense that the item is left untouched and picked up again once the suspension
 * ends, but a pipeline sweep must stop instead of attempting every remaining item.
 */
public record AiError(
    String code,
    String category,
    boolean retryable,
    String message,
    UUID correlationId,
    Map<String, Object> details) {

  public static final String CAPABILITY_SUSPENDED_CODE = "AI_CAPABILITY_SUSPENDED";
  public static final String SUSPENDED_CATEGORY = "suspended";

  /** A call that was not made because its input is over a known limit of the capability. */
  public static final String INPUT_LIMIT_CATEGORY = "input_limit";

  /** Detail set on a refused request that is blamed on its own input, not on the capability. */
  public static final String REJECTED_INPUT_DETAIL = "rejectedInput";

  public static AiError inputLimitExceeded(String message) {
    return new AiError(
        "AI_INPUT_LIMIT_EXCEEDED",
        INPUT_LIMIT_CATEGORY,
        false,
        message,
        UUID.randomUUID(),
        Map.of());
  }

  /**
   * This input cannot be processed as it is: it is over a known limit, or the AI service refused it
   * while there is no sign that the capability itself is broken. The work is set aside, its data
   * kept, and the capability stays available (docs/adr/0019-pipeline-automation.md).
   */
  public boolean rejectsThisInput() {
    return INPUT_LIMIT_CATEGORY.equals(category)
        || (details != null && Boolean.TRUE.equals(details.get(REJECTED_INPUT_DETAIL)));
  }

  /** The same failure, blamed on its input. */
  public AiError asRejectionOfThisInput() {
    Map<String, Object> marked = new LinkedHashMap<>();
    if (details != null) {
      marked.putAll(details);
    }
    marked.put(REJECTED_INPUT_DETAIL, true);
    return new AiError(code, category, retryable, message, correlationId, marked);
  }

  /** A call that was not made because the time budget of the surrounding work is spent. */
  public static final String TIME_BUDGET_CATEGORY = "time_budget";

  public boolean timeBudgetExceeded() {
    return TIME_BUDGET_CATEGORY.equals(category);
  }

  /** Detail set on the suspended failure of the very request that made the capability suspect. */
  public static final String REJECTED_THIS_REQUEST_DETAIL = "rejectedThisRequest";

  private static final String PROVIDER_CATEGORY = "provider";
  private static final String PROVIDER_FAILURE_SCOPE = "providerFailureScope";
  private static final String CONFIGURATION_SCOPE = "configuration";
  private static final String REQUEST_SCOPE = "request";

  /**
   * The capability is suspended: the call was not made, or its provider configuration was refused.
   */
  public boolean capabilitySuspended() {
    return SUSPENDED_CATEGORY.equals(category);
  }

  /**
   * The capability was suspended because of <em>this</em> request: the provider rejected it with a
   * status that looks specific to it, before the capability was known to work. This request may be
   * the bad one, so the caller moves its item behind the others, and a different item probes the
   * capability next. A suspended failure that merely skipped the call does not carry this: the
   * items it skipped are not suspect and keep their place.
   */
  public boolean suspendedByRejectingThisRequest() {
    return capabilitySuspended()
        && details != null
        && Boolean.TRUE.equals(details.get(REJECTED_THIS_REQUEST_DETAIL));
  }

  /**
   * The Java backend and the AI service disagree on the request or response itself (an invalid
   * request, a contract violation). This comes from the deployment far more often than from one
   * item, so it suspends the capability instead of failing items
   * (docs/adr/0019-pipeline-automation.md).
   */
  public boolean contractMismatch() {
    return !retryable && ("contract".equals(category) || "request".equals(category));
  }

  /**
   * The provider refused its <em>configuration</em> (credentials, model, endpoint), as opposed to
   * one request: a permanent failure that affects every call of the capability until it is fixed.
   */
  public boolean rejectsProviderConfiguration() {
    return rejectsWithScope(CONFIGURATION_SCOPE);
  }

  /**
   * The provider rejected this request with a permanent status that looks specific to it (HTTP 400,
   * 413, 422…). It <em>looks</em> specific only: the same status is what a model parameter the
   * provider does not accept produces for every request. It is content-specific once the capability
   * is known to work for other requests, which only the caller's history can tell
   * (docs/adr/0018-pipeline-reliability.md).
   */
  public boolean rejectsSingleRequest() {
    return rejectsWithScope(REQUEST_SCOPE);
  }

  private boolean rejectsWithScope(String scope) {
    return PROVIDER_CATEGORY.equals(category)
        && !retryable
        && details != null
        && scope.equals(details.get(PROVIDER_FAILURE_SCOPE));
  }
}
