package org.signalengine.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.slf4j.LoggerFactory;

class HttpAiCapabilityInvokerTest {

  record EchoPayload(String text) {}

  record EchoResult(String echoed, int characterCount) {}

  private FakeAiCapabilityServer server;
  private HttpAiCapabilityInvoker invoker;

  @BeforeEach
  void setUp() {
    server = new FakeAiCapabilityServer();
    invoker = invokerFor(server.baseUri(), Duration.ofSeconds(5));
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private static HttpAiCapabilityInvoker invokerFor(URI baseUri, Duration timeout) {
    return new HttpAiCapabilityInvoker(
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
        new AiFoundationProperties(baseUri, timeout, Duration.ofSeconds(2)));
  }

  private static AiCapabilityRequest echoRequest(UUID correlationId) {
    return new AiCapabilityRequest("echo", 1, new EchoPayload("hi"), correlationId);
  }

  @Test
  void mapsASuccessEnvelopeToAProducedResult() {
    UUID correlationId = UUID.randomUUID();
    server.replyWith(200, successBody(correlationId));

    AiCapabilityOutcome<EchoResult> outcome =
        invoker.invoke(echoRequest(correlationId), EchoResult.class);

    assertThat(outcome).isInstanceOf(Produced.class);
    Produced<EchoResult> produced = (Produced<EchoResult>) outcome;
    assertThat(produced.result()).isEqualTo(new EchoResult("hi", 2));
    assertThat(produced.metadata().promptVersion()).isEqualTo("echo/v1");
    assertThat(produced.correlationId()).isEqualTo(correlationId);
  }

  @Test
  void sendsTheVersionedEndpointTheEnvelopeAndTheCorrelationHeader() {
    UUID correlationId = UUID.randomUUID();
    server.replyWith(200, successBody(correlationId));

    invoker.invoke(echoRequest(correlationId), EchoResult.class);

    FakeAiCapabilityServer.ReceivedRequest request = server.lastRequest();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.path()).isEqualTo("/capabilities/echo/v1");
    assertThat(request.correlationHeader()).isEqualTo(correlationId.toString());
    assertThat(request.body())
        .contains("\"capability\":\"echo\"")
        .contains("\"contractVersion\":1")
        .contains("\"correlationId\":\"" + correlationId + "\"")
        .contains("\"text\":\"hi\"");
  }

  @Test
  void mapsATypedPythonErrorToFailedWithItsRetryableFlag() {
    UUID correlationId = UUID.randomUUID();
    server.replyWith(
        422,
        """
        {"correlationId":"%s","contractVersion":1,"capability":"echo","status":"error",
         "error":{"code":"AI_OUTPUT_INVALID","category":"ai_output","retryable":false,
                  "message":"bad output","correlationId":"%s"}}
        """
            .formatted(correlationId, correlationId));

    AiCapabilityOutcome<EchoResult> outcome =
        invoker.invoke(echoRequest(correlationId), EchoResult.class);

    assertThat(outcome).isInstanceOf(Failed.class);
    Failed<EchoResult> failed = (Failed<EchoResult>) outcome;
    assertThat(failed.error().code()).isEqualTo("AI_OUTPUT_INVALID");
    assertThat(failed.error().category()).isEqualTo("ai_output");
    assertThat(failed.error().retryable()).isFalse();
    assertThat(failed.error().correlationId()).isEqualTo(correlationId);
  }

  @Test
  void mapsARetryablePythonErrorToFailedRetryable() {
    UUID correlationId = UUID.randomUUID();
    server.replyWith(
        503,
        """
        {"correlationId":"%s","contractVersion":1,"capability":"echo","status":"error",
         "error":{"code":"AI_PROVIDER_UNAVAILABLE","category":"provider","retryable":true,
                  "message":"ollama down","correlationId":"%s"}}
        """
            .formatted(correlationId, correlationId));

    AiCapabilityOutcome<EchoResult> outcome =
        invoker.invoke(echoRequest(correlationId), EchoResult.class);

    assertThat(((Failed<EchoResult>) outcome).error().retryable()).isTrue();
  }

  @Test
  void treatsANonEnvelopeBodyAsANonRetryableContractViolation() {
    server.replyWith(200, "<html>not json</html>");

    AiCapabilityOutcome<EchoResult> outcome =
        invoker.invoke(echoRequest(UUID.randomUUID()), EchoResult.class);

    Failed<EchoResult> failed = (Failed<EchoResult>) outcome;
    assertThat(failed.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
    assertThat(failed.error().retryable()).isFalse();
  }

  @Test
  void treatsAResultThatDoesNotMatchTheTypeAsAContractViolation() {
    UUID correlationId = UUID.randomUUID();
    server.replyWith(
        200,
        """
        {"correlationId":"%s","contractVersion":1,"capability":"echo","status":"success",
         "result":{"echoed":"hi","characterCount":"not-a-number"},
         "meta":{"provider":"p","model":"m","promptVersion":"echo/v1","durationMillis":1}}
        """
            .formatted(correlationId));

    AiCapabilityOutcome<EchoResult> outcome =
        invoker.invoke(echoRequest(correlationId), EchoResult.class);

    assertThat(((Failed<EchoResult>) outcome).error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
  }

  @Test
  void rejectsASuccessResponseForADifferentCapability() {
    UUID correlationId = UUID.randomUUID();
    server.replyWith(
        200,
        """
        {"correlationId":"%s","contractVersion":1,"capability":"summarize","status":"success",
         "result":{"echoed":"hi","characterCount":2},
         "meta":{"provider":"p","model":"m","promptVersion":"echo/v1","durationMillis":1}}
        """
            .formatted(correlationId));

    AiCapabilityOutcome<EchoResult> outcome =
        invoker.invoke(echoRequest(correlationId), EchoResult.class);

    assertThat(outcome).isInstanceOf(Failed.class);
    Failed<EchoResult> failed = (Failed<EchoResult>) outcome;
    assertThat(failed.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
    assertThat(failed.error().retryable()).isFalse();
    assertThat(failed.error().message()).contains("summarize");
  }

  @Test
  void rejectsASuccessResponseDeclaringADifferentContractVersion() {
    UUID correlationId = UUID.randomUUID();
    server.replyWith(
        200,
        """
        {"correlationId":"%s","contractVersion":2,"capability":"echo","status":"success",
         "result":{"echoed":"hi","characterCount":2},
         "meta":{"provider":"p","model":"m","promptVersion":"echo/v1","durationMillis":1}}
        """
            .formatted(correlationId));

    Failed<EchoResult> failed =
        (Failed<EchoResult>) invoker.invoke(echoRequest(correlationId), EchoResult.class);

    assertThat(failed.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
    assertThat(failed.error().retryable()).isFalse();
  }

  @Test
  void rejectsAResponseWhoseCorrelationIdIsNotTheOneRequested() {
    server
        .withoutCorrelationEcho()
        .replyWith(
            200,
            """
            {"correlationId":"11111111-1111-1111-1111-111111111111","contractVersion":1,
             "capability":"echo","status":"success",
             "result":{"echoed":"hi","characterCount":2},
             "meta":{"provider":"p","model":"m","promptVersion":"echo/v1","durationMillis":1}}
            """);

    Failed<EchoResult> failed =
        (Failed<EchoResult>) invoker.invoke(echoRequest(UUID.randomUUID()), EchoResult.class);

    assertThat(failed.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
    assertThat(failed.error().retryable()).isFalse();
    assertThat(failed.error().message()).contains("correlation id");
  }

  @Test
  void rejectsAResponseWithoutACorrelationId() {
    server
        .withoutCorrelationEcho()
        .replyWith(
            200,
            """
            {"contractVersion":1,"capability":"echo","status":"success",
             "result":{"echoed":"hi","characterCount":2},
             "meta":{"provider":"p","model":"m","promptVersion":"echo/v1","durationMillis":1}}
            """);

    Failed<EchoResult> failed =
        (Failed<EchoResult>) invoker.invoke(echoRequest(UUID.randomUUID()), EchoResult.class);

    assertThat(failed.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
    assertThat(failed.error().retryable()).isFalse();
  }

  @Test
  void rejectsASuccessResponseWithoutMetadata() {
    UUID correlationId = UUID.randomUUID();
    server.replyWith(
        200,
        """
        {"correlationId":"%s","contractVersion":1,"capability":"echo","status":"success",
         "result":{"echoed":"hi","characterCount":2}}
        """
            .formatted(correlationId));

    Failed<EchoResult> failed =
        (Failed<EchoResult>) invoker.invoke(echoRequest(correlationId), EchoResult.class);

    assertThat(failed.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
    assertThat(failed.error().retryable()).isFalse();
    assertThat(failed.error().message()).contains("metadata");
  }

  @Test
  void rejectsASuccessResponseWithIncompleteMetadata() {
    UUID correlationId = UUID.randomUUID();
    server.replyWith(
        200,
        """
        {"correlationId":"%s","contractVersion":1,"capability":"echo","status":"success",
         "result":{"echoed":"hi","characterCount":2},
         "meta":{"provider":"p","model":"m","promptVersion":""}}
        """
            .formatted(correlationId));

    Failed<EchoResult> failed =
        (Failed<EchoResult>) invoker.invoke(echoRequest(correlationId), EchoResult.class);

    assertThat(failed.error().code()).isEqualTo("AI_CONTRACT_VIOLATION");
    assertThat(failed.error().retryable()).isFalse();
  }

  @Test
  void aMismatchedResponseNeverBecomesAProducedResult() {
    UUID correlationId = UUID.randomUUID();
    server.replyWith(
        200,
        """
        {"correlationId":"%s","contractVersion":1,"capability":"relevance","status":"success",
         "result":{"echoed":"hi","characterCount":2},
         "meta":{"provider":"p","model":"m","promptVersion":"echo/v1","durationMillis":1}}
        """
            .formatted(correlationId));

    AiCapabilityOutcome<EchoResult> outcome =
        invoker.invoke(echoRequest(correlationId), EchoResult.class);

    assertThat(outcome).isNotInstanceOf(Produced.class);
    assertThat(outcome).isInstanceOf(Failed.class);
  }

  @Test
  void passesThroughATypedErrorWhoseContractVersionDiffersFromAnUnsupportedRequestedVersion() {
    UUID correlationId = UUID.randomUUID();
    server.replyWith(
        400,
        """
        {"correlationId":"%s","contractVersion":1,"capability":"echo","status":"error",
         "error":{"code":"AI_REQUEST_INVALID","category":"request","retryable":false,
                  "message":"capability 'echo' does not support contract version 2",
                  "correlationId":"%s"}}
        """
            .formatted(correlationId, correlationId));

    AiCapabilityRequest unsupportedVersion =
        new AiCapabilityRequest("echo", 2, new EchoPayload("hi"), correlationId);
    Failed<EchoResult> failed =
        (Failed<EchoResult>) invoker.invoke(unsupportedVersion, EchoResult.class);

    assertThat(failed.error().code()).isEqualTo("AI_REQUEST_INVALID");
    assertThat(failed.error().retryable()).isFalse();
  }

  @Test
  void mapsAJavaSideTimeoutToARetryableTimeout() {
    HttpAiCapabilityInvoker impatient = invokerFor(server.baseUri(), Duration.ofMillis(150));
    server.delay(600).replyWith(200, successBody(UUID.randomUUID()));

    AiCapabilityOutcome<EchoResult> outcome =
        impatient.invoke(echoRequest(UUID.randomUUID()), EchoResult.class);

    Failed<EchoResult> failed = (Failed<EchoResult>) outcome;
    assertThat(failed.error().category()).isEqualTo("timeout");
    assertThat(failed.error().retryable()).isTrue();
  }

  @Test
  void boundsAServerThatNeverRespondsAndLeavesTheInvokerUsableAfterward() {
    Duration configuredTimeout = Duration.ofMillis(200);
    HttpAiCapabilityInvoker impatient = invokerFor(server.baseUri(), configuredTimeout);
    // Far longer than the configured timeout: the server never replies within it, simulating the
    // production incident where a call blocked for hours past its configured ceiling.
    server.delay(10_000).replyWith(200, successBody(UUID.randomUUID()));

    Instant startedAt = Instant.now();
    AiCapabilityOutcome<EchoResult> outcome =
        impatient.invoke(echoRequest(UUID.randomUUID()), EchoResult.class);
    Duration testMeasuredElapsed = Duration.between(startedAt, Instant.now());

    // Bounded well under the server's 10s delay — proves the call did not wait for it.
    assertThat(testMeasuredElapsed).isLessThan(Duration.ofSeconds(5));
    Failed<EchoResult> failed = (Failed<EchoResult>) outcome;
    assertThat(failed.error().code()).isEqualTo("AI_TRANSPORT_TIMEOUT");
    assertThat(failed.error().category()).isEqualTo("timeout");
    assertThat(failed.error().retryable()).isTrue();

    // The timed-out exchange must have been cancelled/discarded, not left occupying the
    // connection: a fresh request through the same invoker succeeds normally right after.
    UUID secondCorrelationId = UUID.randomUUID();
    server.delay(0).replyWith(200, successBody(secondCorrelationId));
    AiCapabilityOutcome<EchoResult> secondOutcome =
        impatient.invoke(echoRequest(secondCorrelationId), EchoResult.class);
    assertThat(secondOutcome).isInstanceOf(Produced.class);
  }

  @Test
  void timeoutLogReportsActualElapsedTimeNotTheConfiguredTimeout() {
    Duration configuredTimeout = Duration.ofMillis(200);
    HttpAiCapabilityInvoker impatient = invokerFor(server.baseUri(), configuredTimeout);
    server.delay(10_000).replyWith(200, successBody(UUID.randomUUID()));

    Logger invokerLogger = (Logger) LoggerFactory.getLogger(HttpAiCapabilityInvoker.class);
    ListAppender<ILoggingEvent> capturedLogs = new ListAppender<>();
    capturedLogs.start();
    invokerLogger.addAppender(capturedLogs);

    Instant startedAt = Instant.now();
    try {
      impatient.invoke(echoRequest(UUID.randomUUID()), EchoResult.class);
    } finally {
      invokerLogger.detachAppender(capturedLogs);
    }
    Duration testMeasuredElapsed = Duration.between(startedAt, Instant.now());

    ILoggingEvent timeoutLog =
        capturedLogs.list.stream()
            .filter(event -> event.getFormattedMessage().contains("timed out"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no timeout log line was captured"));
    String message = timeoutLog.getFormattedMessage();
    assertThat(message).contains("echo"); // capability
    assertThat(message).contains(configuredTimeout.toString()); // configured ceiling, verbatim
    assertThat(message).contains("provider/model unavailable"); // no response was ever received

    // The logged elapsed duration must reflect what actually happened (close to the test's own
    // independently measured elapsed time), not simply echo the configured timeout back — the
    // exact bug that made an 11-hour hang look, in the logs, like a 240-second one.
    Duration loggedElapsed = extractElapsedDuration(message);
    assertThat(loggedElapsed).isGreaterThanOrEqualTo(configuredTimeout);
    assertThat(loggedElapsed).isLessThanOrEqualTo(testMeasuredElapsed.plusSeconds(2));
  }

  private static Duration extractElapsedDuration(String message) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("timed out after (PT[^\\s]+)").matcher(message);
    assertThat(matcher.find()).as("message contains an elapsed duration: %s", message).isTrue();
    return Duration.parse(matcher.group(1));
  }

  @Test
  void mapsAnUnreachableServiceToARetryableTransportError() {
    server.close();
    HttpAiCapabilityInvoker offline =
        invokerFor(URI.create("http://127.0.0.1:9"), Duration.ofSeconds(2));

    AiCapabilityOutcome<EchoResult> outcome =
        offline.invoke(echoRequest(UUID.randomUUID()), EchoResult.class);

    Failed<EchoResult> failed = (Failed<EchoResult>) outcome;
    assertThat(failed.error().category()).isEqualTo("transport");
    assertThat(failed.error().retryable()).isTrue();
  }

  private static String successBody(UUID correlationId) {
    return """
        {"correlationId":"%s","contractVersion":1,"capability":"echo","status":"success",
         "result":{"echoed":"hi","characterCount":2},
         "meta":{"provider":"scripted","model":"scripted","promptVersion":"echo/v1","durationMillis":0}}
        """
        .formatted(correlationId);
  }
}
