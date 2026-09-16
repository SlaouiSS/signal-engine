package org.signalengine.infrastructure.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.ai.AiResponseMetadata;
import org.signalengine.infrastructure.ai.AiCapabilityEnvelopes.RequestEnvelope;
import org.signalengine.infrastructure.ai.AiCapabilityEnvelopes.ResponseEnvelope;
import org.signalengine.infrastructure.ai.AiCapabilityEnvelopes.ResponseMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls a versioned capability on the Python AI service over HTTP/JSON with the JDK client
 * (docs/03-technical-spec.md Section 8; docs/adr/0006-ai-java-python-foundation.md).
 *
 * <p>Never throws for an expected failure: a transport error, a timeout, an unparseable or
 * schema-invalid response, and a typed Python error all become {@link AiCapabilityOutcome.Failed}
 * with the right {@code retryable} flag (docs/03-technical-spec.md Section 8.5, 13.2).
 *
 * <p>A Python response is untrusted input at this boundary. Before any of its content is used, the
 * envelope must identify itself as the answer to <em>this</em> request — matching correlation id
 * and capability, the requested contract version on a success, and complete provider/model/timing
 * metadata on a success (docs/03-technical-spec.md Section 8.2–8.3). A mismatch is a non-retryable
 * {@code AI_CONTRACT_VIOLATION}; the result is never consumed.
 *
 * <p>It owns its own {@link ObjectMapper}: the wire records here are the only thing it serialises,
 * so it does not depend on the application-wide Jackson configuration.
 *
 * <p><strong>A single call can never block the caller past {@code requestTimeout}.</strong> The
 * per-request {@code HttpRequest.timeout(Duration)} below is the JDK client's own bound, but an
 * incident showed it is not always enough on its own — one call once blocked far past its
 * configured ceiling. The call is therefore made with {@link HttpClient#sendAsync} and awaited with
 * an independent {@link CompletableFuture#get(long, TimeUnit) get(timeout)} on the calling thread;
 * if that deadline is reached first, the future is {@link CompletableFuture#cancel(boolean)
 * cancelled}, which the JDK client wires to aborting the underlying exchange and discarding its
 * connection rather than returning it to the keep-alive pool for reuse. No extra thread or executor
 * is created — the async exchange still runs on the shared {@link HttpClient}'s own engine, and the
 * calling thread's bounded wait is the only addition.
 */
final class HttpAiCapabilityInvoker implements AiCapabilityInvoker {

  private static final Logger log = LoggerFactory.getLogger(HttpAiCapabilityInvoker.class);
  private static final String CORRELATION_HEADER = "X-Correlation-Id";

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final URI agentsBaseUrl;
  private final Duration requestTimeout;

  HttpAiCapabilityInvoker(HttpClient httpClient, AiFoundationProperties properties) {
    this.httpClient = httpClient;
    this.objectMapper = JsonMapper.builder().build();
    this.agentsBaseUrl = properties.agentsBaseUrl();
    this.requestTimeout = properties.requestTimeout();
  }

  @Override
  public <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> resultType) {
    UUID correlationId = request.correlationId();

    byte[] body;
    try {
      body =
          objectMapper.writeValueAsBytes(
              new RequestEnvelope(
                  correlationId.toString(),
                  request.contractVersion(),
                  request.capability(),
                  request.payload()));
    } catch (JacksonException notSerialisable) {
      return failed(
          "AI_REQUEST_INVALID", "request", false, correlationId, "payload is not serialisable");
    }

    long startedAtNanos = System.nanoTime();
    CompletableFuture<HttpResponse<String>> future =
        httpClient.sendAsync(
            HttpRequest.newBuilder(endpointFor(request))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(CORRELATION_HEADER, correlationId.toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    HttpResponse<String> response;
    try {
      response = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException enforcedTimeout) {
      // The JDK client's own per-request timeout did not fire in time (or at all) — this
      // independent, calling-thread deadline is what actually bounds the call. Cancelling the
      // future aborts the underlying exchange rather than leaving it running unattended.
      future.cancel(true);
      return timedOut(request, correlationId, startedAtNanos);
    } catch (ExecutionException executionFailure) {
      Throwable cause = executionFailure.getCause();
      if (cause instanceof HttpTimeoutException) {
        return timedOut(request, correlationId, startedAtNanos);
      }
      if (cause instanceof IOException networkError) {
        log.warn(
            "AI capability '{}' is unreachable: {}", request.capability(), networkError.toString());
        return failed(
            "AI_TRANSPORT_ERROR",
            "transport",
            true,
            correlationId,
            "the AI service is unreachable: " + networkError.getMessage());
      }
      future.cancel(true);
      return failed(
          "AI_TRANSPORT_ERROR",
          "transport",
          true,
          correlationId,
          "the AI call failed unexpectedly: " + cause);
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      return failed(
          "AI_TRANSPORT_ERROR", "transport", true, correlationId, "the AI call was interrupted");
    }

    return mapResponse(response, resultType, request);
  }

  /**
   * Builds the {@code AI_TRANSPORT_TIMEOUT} failure for a call that did not complete within {@code
   * requestTimeout}, logging the call's actual elapsed wall-clock duration alongside the configured
   * ceiling — never presenting the configured value as if it were the measured one. Provider and
   * model are never available here: a timeout means no response, and therefore no metadata, was
   * ever received.
   */
  private <R> AiCapabilityOutcome<R> timedOut(
      AiCapabilityRequest request, UUID correlationId, long startedAtNanos) {
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAtNanos);
    log.warn(
        "AI capability '{}' timed out after {} (configured timeout was {}); provider/model "
            + "unavailable — no response was ever received; correlationId={}",
        request.capability(),
        elapsed,
        requestTimeout,
        correlationId);
    return failed(
        "AI_TRANSPORT_TIMEOUT",
        "timeout",
        true,
        correlationId,
        "the AI service did not respond within " + requestTimeout);
  }

  private <R> AiCapabilityOutcome<R> mapResponse(
      HttpResponse<String> response, Class<R> resultType, AiCapabilityRequest request) {
    UUID requestCorrelationId = request.correlationId();
    ResponseEnvelope envelope = parse(response.body());
    boolean successStatus = response.statusCode() / 100 == 2;

    if (envelope == null) {
      boolean retryable = !successStatus && response.statusCode() / 100 == 5;
      return failed(
          retryable ? "AI_TRANSPORT_ERROR" : "AI_CONTRACT_VIOLATION",
          retryable ? "transport" : "contract",
          retryable,
          requestCorrelationId,
          "the AI service returned an unreadable body (HTTP " + response.statusCode() + ")");
    }

    AiCapabilityOutcome<R> identityViolation =
        envelopeIdentityViolation(envelope, request, successStatus);
    if (identityViolation != null) {
      return identityViolation;
    }

    if ("error".equals(envelope.status()) && envelope.error() != null) {
      return failedFromPython(envelope.error(), requestCorrelationId);
    }
    if (!successStatus || !"success".equals(envelope.status()) || envelope.result() == null) {
      return failed(
          "AI_CONTRACT_VIOLATION",
          "contract",
          false,
          requestCorrelationId,
          "the AI response envelope is neither a valid success nor a valid error");
    }

    AiResponseMetadata metadata = validSuccessMetadata(envelope.meta());
    if (metadata == null) {
      return failed(
          "AI_CONTRACT_VIOLATION",
          "contract",
          false,
          requestCorrelationId,
          "the AI success response is missing required provider/model/timing metadata");
    }
    return bindResult(envelope, resultType, metadata, requestCorrelationId);
  }

  /**
   * Rejects an envelope that does not identify itself as the response to {@code request}: a missing
   * or non-matching correlation id or capability (checked on every response), or — when the
   * envelope is about to be consumed as a success — a contract version other than the one called. A
   * typed error legitimately reports the service's own contract version even for a request that
   * named an unsupported one, so the version is only enforced on the success path. Any mismatch is
   * a non-retryable {@code AI_CONTRACT_VIOLATION} (docs/03-technical-spec.md Section 8.2–8.3).
   */
  private <R> AiCapabilityOutcome<R> envelopeIdentityViolation(
      ResponseEnvelope envelope, AiCapabilityRequest request, boolean successStatus) {
    UUID requestCorrelationId = request.correlationId();

    UUID responseCorrelationId = parseUuid(envelope.correlationId());
    if (responseCorrelationId == null || !responseCorrelationId.equals(requestCorrelationId)) {
      return failed(
          "AI_CONTRACT_VIOLATION",
          "contract",
          false,
          requestCorrelationId,
          "the AI response correlation id '"
              + envelope.correlationId()
              + "' does not match the request");
    }

    if (!request.capability().equals(envelope.capability())) {
      return failed(
          "AI_CONTRACT_VIOLATION",
          "contract",
          false,
          requestCorrelationId,
          "the AI response is for capability '"
              + envelope.capability()
              + "', not '"
              + request.capability()
              + "'");
    }

    Integer responseContractVersion = envelope.contractVersion();
    boolean consumedAsSuccess = successStatus && "success".equals(envelope.status());
    if (consumedAsSuccess
        && (responseContractVersion == null
            || responseContractVersion != request.contractVersion())) {
      return failed(
          "AI_CONTRACT_VIOLATION",
          "contract",
          false,
          requestCorrelationId,
          "the AI success response declares contract version "
              + responseContractVersion
              + ", not "
              + request.contractVersion());
    }
    return null;
  }

  private <R> AiCapabilityOutcome<R> bindResult(
      ResponseEnvelope envelope,
      Class<R> resultType,
      AiResponseMetadata metadata,
      UUID correlationId) {
    try {
      R result = objectMapper.treeToValue(envelope.result(), resultType);
      return new Produced<>(result, metadata, correlationId);
    } catch (JacksonException wrongShape) {
      return failed(
          "AI_CONTRACT_VIOLATION",
          "contract",
          false,
          correlationId,
          "the AI result does not match " + resultType.getSimpleName());
    }
  }

  private ResponseEnvelope parse(String rawBody) {
    if (rawBody == null || rawBody.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(rawBody, ResponseEnvelope.class);
    } catch (JacksonException notAnEnvelope) {
      return null;
    }
  }

  private <R> AiCapabilityOutcome<R> failedFromPython(
      AiCapabilityEnvelopes.ErrorBody error, UUID correlationId) {
    return new Failed<>(
        new AiError(
            valueOr(error.code(), "AI_UNKNOWN"),
            valueOr(error.category(), "internal"),
            error.retryable() == null || error.retryable(),
            valueOr(error.message(), "the AI service reported an unspecified error"),
            correlationId,
            error.details()));
  }

  private static <R> AiCapabilityOutcome<R> failed(
      String code, String category, boolean retryable, UUID correlationId, String message) {
    return new Failed<>(new AiError(code, category, retryable, message, correlationId, Map.of()));
  }

  private URI endpointFor(AiCapabilityRequest request) {
    String base = agentsBaseUrl.toString();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return URI.create(
        base + "/capabilities/" + request.capability() + "/v" + request.contractVersion());
  }

  private static AiResponseMetadata validSuccessMetadata(ResponseMeta meta) {
    if (meta == null
        || isBlank(meta.provider())
        || isBlank(meta.model())
        || isBlank(meta.promptVersion())
        || meta.durationMillis() == null
        || meta.durationMillis() < 0) {
      return null;
    }
    return new AiResponseMetadata(
        meta.provider(), meta.model(), meta.promptVersion(), meta.durationMillis());
  }

  private static UUID parseUuid(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
