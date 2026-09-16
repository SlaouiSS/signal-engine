package org.signalengine.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.infrastructure.rag.generation.AiCapabilityAnswerGenerator.CitationPayload;
import org.signalengine.infrastructure.rag.generation.AiCapabilityAnswerGenerator.PassagePayload;
import org.signalengine.infrastructure.rag.generation.AiCapabilityAnswerGenerator.RequestPayload;
import org.signalengine.infrastructure.rag.generation.AiCapabilityAnswerGenerator.ResultPayload;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replays the shared {@code answer} contract fixtures (owned by {@code agents/contract/}) through
 * the Java HTTP client, the way {@link EmbedCapabilityContractTest} does for {@code embed}. The
 * Python suite validates the same files against the Pydantic models.
 */
class AnswerCapabilityContractTest {

  private static final String EXAMPLES = "/contract/ai/examples/";
  private static final UUID FIXTURE_CORRELATION_ID =
      UUID.fromString("a1b2c3d4-0000-4000-8000-000000000009");

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
    JsonNode payload = fixture("request.answer.json").get("payload");
    List<PassagePayload> passages = new ArrayList<>();
    for (JsonNode passage : payload.get("passages")) {
      passages.add(
          new PassagePayload(
              passage.get("passageId").asString(),
              passage.get("text").asString(),
              passage.has("source") ? passage.get("source").asString() : null));
    }
    return new AiCapabilityRequest(
        "answer",
        1,
        new RequestPayload(payload.get("question").asString(), passages),
        FIXTURE_CORRELATION_ID);
  }

  @Test
  void theClientRequestMatchesTheRequestFixture() throws IOException {
    server.replyWith(200, fixtureText("response.answer-success.json"));

    invoker().invoke(fixtureRequest(), ResultPayload.class);

    JsonNode sent = objectMapper.readTree(server.lastRequest().body());
    assertThat(sent).isEqualTo(fixture("request.answer.json"));
  }

  @Test
  void theSuccessFixtureBindsToTheJavaResult() throws IOException {
    server.replyWith(200, fixtureText("response.answer-success.json"));

    AiCapabilityOutcome<ResultPayload> outcome =
        invoker().invoke(fixtureRequest(), ResultPayload.class);

    Produced<ResultPayload> produced = (Produced<ResultPayload>) outcome;
    assertThat(produced.result().answered()).isTrue();
    assertThat(produced.result().answer()).contains("highest-risk");
    assertThat(produced.result().citations())
        .extracting(CitationPayload::passageId)
        .containsExactly("doc-eu-ai-act::0");
    assertThat(produced.metadata().promptVersion()).isEqualTo("answer/v1");
  }
}
