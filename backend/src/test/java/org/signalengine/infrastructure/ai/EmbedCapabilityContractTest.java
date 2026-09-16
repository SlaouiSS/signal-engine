package org.signalengine.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.infrastructure.rag.embedding.AiCapabilityEmbeddingModel.EmbedPayload;
import org.signalengine.infrastructure.rag.embedding.AiCapabilityEmbeddingModel.EmbedResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replays the shared {@code embed} contract fixtures (owned by {@code agents/contract/}) through
 * the Java HTTP client, the way {@link AiCapabilityContractTest} does for {@code echo}.
 */
class EmbedCapabilityContractTest {

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
    JsonNode payload = fixture("request.embed.json").get("payload");
    List<String> texts =
        List.of(payload.get("texts").get(0).asString(), payload.get("texts").get(1).asString());
    return new AiCapabilityRequest(
        "embed",
        1,
        new EmbedPayload(texts, payload.get("role").asString()),
        FIXTURE_CORRELATION_ID);
  }

  @Test
  void theClientRequestMatchesTheRequestFixture() throws IOException {
    server.replyWith(200, fixtureText("response.embed-success.json"));

    invoker().invoke(fixtureRequest(), EmbedResult.class);

    JsonNode sent = objectMapper.readTree(server.lastRequest().body());
    assertThat(sent).isEqualTo(fixture("request.embed.json"));
  }

  @Test
  void theSuccessFixtureBindsToTheJavaResult() throws IOException {
    server.replyWith(200, fixtureText("response.embed-success.json"));

    AiCapabilityOutcome<EmbedResult> outcome =
        invoker().invoke(fixtureRequest(), EmbedResult.class);

    Produced<EmbedResult> produced = (Produced<EmbedResult>) outcome;
    assertThat(produced.result().dimension()).isEqualTo(4);
    assertThat(produced.result().vectors()).hasDimensions(2, 4);
    assertThat(produced.result().model()).isEqualTo("embeddinggemma");
    assertThat(produced.metadata().promptVersion()).isEqualTo("embed/v1");
  }
}
