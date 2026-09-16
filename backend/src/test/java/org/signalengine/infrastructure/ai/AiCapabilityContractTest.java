package org.signalengine.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the Java client against the shared contract fixtures that Python owns ({@code
 * agents/contract/}, copied onto the test classpath by {@code build.gradle.kts}). The Python suite
 * validates the same files against the Pydantic models, so agreement here means the Java records,
 * the fixtures, and the Pydantic models all describe one contract.
 */
class AiCapabilityContractTest {

  record EchoPayload(String text, String note) {}

  record EchoResult(String echoed, int characterCount) {}

  private static final String EXAMPLES = "/contract/ai/examples/";
  private static final UUID FIXTURE_CORRELATION_ID =
      UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private FakeAiCapabilityServer server;

  @BeforeEach
  void setUp() {
    server = new FakeAiCapabilityServer();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private JsonNode fixture(String name) throws IOException {
    try (var in = getClass().getResourceAsStream(EXAMPLES + name)) {
      assertThat(in).as("fixture " + name + " on the classpath").isNotNull();
      return objectMapper.readTree(in.readAllBytes());
    }
  }

  private String fixtureText(String name) throws IOException {
    try (var in = getClass().getResourceAsStream(EXAMPLES + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private HttpAiCapabilityInvoker invoker() {
    return new HttpAiCapabilityInvoker(
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
        new AiFoundationProperties(server.baseUri(), Duration.ofSeconds(5), Duration.ofSeconds(2)));
  }

  private AiCapabilityRequest fixtureRequest() {
    return new AiCapabilityRequest(
        "echo",
        1,
        new EchoPayload("Signal Engine ships incrementally.", null),
        FIXTURE_CORRELATION_ID);
  }

  private <R> AiCapabilityOutcome<R> replay(String responseFixture, int status, Class<R> type)
      throws IOException {
    server.replyWith(status, fixtureText(responseFixture));
    return invoker().invoke(fixtureRequest(), type);
  }

  @Test
  void publishedSchemaArtifactIsOnTheClasspath() {
    assertThat(getClass().getResource("/contract/ai/ai-capability.v1.schema.json")).isNotNull();
  }

  @Test
  void theRequestTheClientSendsMatchesTheRequestFixture() throws IOException {
    server.replyWith(200, fixtureText("response.success.json"));

    invoker().invoke(fixtureRequest(), EchoResult.class);

    JsonNode sent = objectMapper.readTree(server.lastRequest().body());
    assertThat(sent).isEqualTo(fixture("request.echo.json"));
  }

  @Test
  void successFixtureMapsToProduced() throws IOException {
    AiCapabilityOutcome<EchoResult> outcome =
        replay("response.success.json", 200, EchoResult.class);

    Produced<EchoResult> produced = (Produced<EchoResult>) outcome;
    assertThat(produced.result())
        .isEqualTo(new EchoResult("Signal Engine ships incrementally.", 34));
    assertThat(produced.metadata().provider()).isEqualTo("ollama");
    assertThat(produced.correlationId()).isEqualTo(FIXTURE_CORRELATION_ID);
  }

  @Test
  void requestInvalidFixtureMapsToNonRetryableFailure() throws IOException {
    Failed<EchoResult> failed =
        (Failed<EchoResult>) replay("response.error.request-invalid.json", 400, EchoResult.class);

    assertThat(failed.error().code()).isEqualTo("AI_REQUEST_INVALID");
    assertThat(failed.error().category()).isEqualTo("request");
    assertThat(failed.error().retryable()).isFalse();
    assertThat(failed.error().correlationId()).isEqualTo(FIXTURE_CORRELATION_ID);
  }

  @Test
  void outputInvalidFixtureMapsToNonRetryableFailure() throws IOException {
    Failed<EchoResult> failed =
        (Failed<EchoResult>) replay("response.error.output-invalid.json", 422, EchoResult.class);

    assertThat(failed.error().code()).isEqualTo("AI_OUTPUT_INVALID");
    assertThat(failed.error().retryable()).isFalse();
    assertThat(failed.error().details()).containsKey("firstError");
  }

  @Test
  void providerUnavailableFixtureMapsToRetryableFailure() throws IOException {
    Failed<EchoResult> failed =
        (Failed<EchoResult>)
            replay("response.error.provider-unavailable.json", 503, EchoResult.class);

    assertThat(failed.error().code()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
    assertThat(failed.error().retryable()).isTrue();
  }

  @Test
  void providerTimeoutFixtureMapsToRetryableFailure() throws IOException {
    Failed<EchoResult> failed =
        (Failed<EchoResult>) replay("response.error.provider-timeout.json", 504, EchoResult.class);

    assertThat(failed.error().code()).isEqualTo("AI_PROVIDER_TIMEOUT");
    assertThat(failed.error().category()).isEqualTo("timeout");
    assertThat(failed.error().retryable()).isTrue();
  }
}
