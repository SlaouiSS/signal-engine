package org.signalengine.application.ai;

import java.util.Map;
import java.util.UUID;

/**
 * A typed AI capability failure (docs/03-technical-spec.md Section 13.5). Either returned by the
 * Python service or synthesised by the Java adapter for a transport/contract failure.
 *
 * <ul>
 *   <li>{@code code} — a stable machine-readable string (e.g. {@code AI_OUTPUT_INVALID}); unknown
 *       codes are tolerated.
 *   <li>{@code category} — one of {@code request}, {@code ai_output}, {@code provider}, {@code
 *       timeout}, {@code internal} (from Python) or {@code transport}, {@code contract}
 *       (synthesised by Java). Kept as text so a new Python category never breaks deserialisation.
 *   <li>{@code retryable} — drives the caller's retry-vs-fail decision (docs/03-technical-spec.md
 *       Section 8.5, 13.2). Transport, timeout and provider failures are retryable; request,
 *       output-validation and contract violations are not.
 *   <li>{@code details} — optional, free-form diagnostic context; never contains secrets.
 * </ul>
 */
public record AiError(
    String code,
    String category,
    boolean retryable,
    String message,
    UUID correlationId,
    Map<String, Object> details) {}
