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
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.infrastructure.rag.chunking.AiCapabilityChunkingModel.BoundaryQuery;
import org.signalengine.infrastructure.rag.chunking.AiCapabilityChunkingModel.BoundaryResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replays the shared {@code semantic-chunk-boundary} contract fixtures (owned by {@code
 * agents/contract/}) through the Java HTTP client, the way {@link AiCapabilityContractTest} does
 * for {@code echo}: agreement here means the Java records, the fixtures and the Pydantic models
 * describe one contract.
 */
class SemanticChunkBoundaryContractTest {

  private static final String EXAMPLES = "/contract/ai/examples/";
  private static final UUID FIXTURE_CORRELATION_ID =
      UUID.fromString("8b1a9953-1f3c-4b8e-9a1d-2c4e6f8a0b2d");

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

  private HttpAiCapabilityInvoker invoker() {
    return new HttpAiCapabilityInvoker(
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
        new AiFoundationProperties(server.baseUri(), Duration.ofSeconds(5), Duration.ofSeconds(2)));
  }

  private JsonNode fixture(String name) throws IOException {
    try (var in = getClass().getResourceAsStream(EXAMPLES + name)) {
      assertThat(in).as("fixture " + name).isNotNull();
      return objectMapper.readTree(in.readAllBytes());
    }
  }

  private String fixtureText(String name) throws IOException {
    try (var in = getClass().getResourceAsStream(EXAMPLES + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private AiCapabilityRequest fixtureRequest() throws IOException {
    JsonNode payload = fixture("request.semantic-chunk-boundary.json").get("payload");
    return new AiCapabilityRequest(
        "semantic-chunk-boundary",
        1,
        new BoundaryQuery(payload.get("prompt").asString(), payload.get("temperature").asDouble()),
        FIXTURE_CORRELATION_ID);
  }

  @Test
  void theClientRequestMatchesTheRequestFixture() throws IOException {
    server.replyWith(200, fixtureText("response.semantic-chunk-boundary-success.json"));

    invoker().invoke(fixtureRequest(), BoundaryResult.class);

    JsonNode sent = objectMapper.readTree(server.lastRequest().body());
    assertThat(sent).isEqualTo(fixture("request.semantic-chunk-boundary.json"));
  }

  @Test
  void theSuccessFixtureBindsToTheJavaResult() throws IOException {
    server.replyWith(200, fixtureText("response.semantic-chunk-boundary-success.json"));

    AiCapabilityOutcome<BoundaryResult> outcome =
        invoker().invoke(fixtureRequest(), BoundaryResult.class);

    Produced<BoundaryResult> produced = (Produced<BoundaryResult>) outcome;
    assertThat(produced.result().rawText()).isEqualTo("[2]");
    assertThat(produced.metadata().promptVersion()).isEqualTo("semantic-chunk-boundary/v1");
    assertThat(produced.correlationId()).isEqualTo(FIXTURE_CORRELATION_ID);
  }
}
