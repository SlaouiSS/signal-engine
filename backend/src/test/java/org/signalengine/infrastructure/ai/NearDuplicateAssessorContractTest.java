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
import org.signalengine.application.dedup.DefaultNearDuplicateAssessor;
import org.signalengine.application.dedup.NearDuplicateAssessor.Candidate;
import org.signalengine.application.dedup.NearDuplicateAssessor.NearDuplicateAssessment.Assessed;
import org.signalengine.application.dedup.NearDuplicateAssessor.Query;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code near-duplicate} capability over the real wire path: the shared fixtures ({@code
 * agents/contract/examples/*near-duplicate*}) replayed through {@link HttpAiCapabilityInvoker} and
 * {@link DefaultNearDuplicateAssessor}. The Python suite validates the same fixtures against the
 * Pydantic models.
 */
class NearDuplicateAssessorContractTest {

  private static final String EXAMPLES = "/contract/ai/examples/";
  private static final UUID C1 = UUID.fromString("b7c2f1a0-0000-0000-0000-000000000001");
  private static final UUID C2 = UUID.fromString("b7c2f1a0-0000-0000-0000-000000000002");

  private final JsonMapper mapper = JsonMapper.builder().build();
  private FakeAiCapabilityServer server;

  @BeforeEach
  void setUp() {
    server = new FakeAiCapabilityServer();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private String fixture(String name) throws IOException {
    try (var in = getClass().getResourceAsStream(EXAMPLES + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private DefaultNearDuplicateAssessor assessor() {
    HttpAiCapabilityInvoker invoker =
        new HttpAiCapabilityInvoker(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
            new AiFoundationProperties(
                server.baseUri(), Duration.ofSeconds(5), Duration.ofSeconds(2)));
    return new DefaultNearDuplicateAssessor(invoker);
  }

  @Test
  void theRequestTheAssessorSendsMatchesTheRequestFixturePayload() throws IOException {
    server.replyWith(200, fixture("response.near-duplicate-success.json"));

    assessor()
        .assess(
            new Query(
                "The central bank raised its benchmark interest rate by half a point today.",
                List.of(
                    new Candidate(
                        C1,
                        "Policymakers lifted the key rate 50 basis points at the meeting that"
                            + " concluded today."),
                    new Candidate(
                        C2,
                        "A new report ranks the city among the best places to launch a startup."))));

    JsonNode sent = mapper.readTree(server.lastRequest().body());
    JsonNode expectedPayload =
        mapper.readTree(fixture("request.near-duplicate.json")).get("payload");
    assertThat(sent.get("capability").asString()).isEqualTo("near-duplicate");
    assertThat(sent.get("payload")).isEqualTo(expectedPayload);
    assertThat(server.lastRequest().path()).isEqualTo("/capabilities/near-duplicate/v1");
  }

  @Test
  void theSuccessFixtureMapsToPerCandidateVerdicts() throws IOException {
    server.replyWith(200, fixture("response.near-duplicate-success.json"));

    Assessed assessed =
        (Assessed)
            assessor()
                .assess(
                    new Query(
                        "candidate", List.of(new Candidate(C1, "one"), new Candidate(C2, "two"))));

    assertThat(assessed.verdicts())
        .anySatisfy(
            v -> {
              assertThat(v.rawInformationItemId()).isEqualTo(C1);
              assertThat(v.sameUnderlyingStory()).isTrue();
            })
        .anySatisfy(
            v -> {
              assertThat(v.rawInformationItemId()).isEqualTo(C2);
              assertThat(v.sameUnderlyingStory()).isFalse();
            });
  }
}
