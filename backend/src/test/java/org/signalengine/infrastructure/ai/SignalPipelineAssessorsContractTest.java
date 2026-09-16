package org.signalengine.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signalengine.application.signal.DefaultImportanceAssessor;
import org.signalengine.application.signal.DefaultRelevanceAssessor;
import org.signalengine.application.signal.DefaultSummaryGenerator;
import org.signalengine.application.signal.ImportanceAssessor;
import org.signalengine.application.signal.RelevanceAssessor;
import org.signalengine.application.signal.RelevanceAssessor.AreaContext;
import org.signalengine.application.signal.RelevanceAssessor.InterestContext;
import org.signalengine.application.signal.SummaryGenerator;
import org.signalengine.application.signal.SummaryGenerator.SourceReference;

/**
 * The relevance / importance / summarize capabilities over the real wire path: the shared fixtures
 * ({@code agents/contract/examples/*}) replayed through {@link HttpAiCapabilityInvoker} and the
 * three assessor adapters. The Python suite validates the same fixtures against the Pydantic
 * models.
 */
class SignalPipelineAssessorsContractTest {

  private static final String EXAMPLES = "/contract/ai/examples/";
  private static final UUID INTEREST_ID = UUID.fromString("1f7b1e2a-0000-4000-8000-000000000010");

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

  private HttpAiCapabilityInvoker invoker() {
    return new HttpAiCapabilityInvoker(
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
        new AiFoundationProperties(server.baseUri(), Duration.ofSeconds(5), Duration.ofSeconds(2)));
  }

  @Test
  void theRelevanceSuccessFixtureMapsToAnAssessedVerdict() throws IOException {
    server.replyWith(200, fixture("response.relevance-success.json"));

    var verdict =
        (RelevanceAssessor.RelevanceVerdict.Assessed)
            new DefaultRelevanceAssessor(invoker())
                .assess(
                    new RelevanceAssessor.Query(
                        "The EU adopted the AI Act.",
                        List.of(
                            new AreaContext("LAW_AND_REGULATION", "Law & Regulation"),
                            new AreaContext("AI_AND_TECHNOLOGY", "AI & Technology")),
                        List.of(
                            new InterestContext(
                                INTEREST_ID, "LAW_AND_REGULATION", "EU technology regulation"))));

    assertThat(verdict.relevant()).isTrue();
    assertThat(verdict.matchedAreaCodes())
        .containsExactlyInAnyOrder("LAW_AND_REGULATION", "AI_AND_TECHNOLOGY");
    assertThat(verdict.matchedInterestIds()).containsExactly(INTEREST_ID);
    assertThat(server.lastRequest().path()).isEqualTo("/capabilities/relevance/v1");
  }

  @Test
  void theImportanceSuccessFixtureMapsToAnAssessedVerdict() throws IOException {
    server.replyWith(200, fixture("response.importance-success.json"));

    var verdict =
        (ImportanceAssessor.ImportanceVerdict.Assessed)
            new DefaultImportanceAssessor(invoker())
                .assess(
                    new ImportanceAssessor.Query(
                        "The EU adopted the AI Act.",
                        "A binding EU regulation of AI.",
                        Set.of("LAW_AND_REGULATION")));

    assertThat(verdict.importantEnough()).isTrue();
    assertThat(verdict.reason()).isNotBlank();
    assertThat(server.lastRequest().path()).isEqualTo("/capabilities/importance/v1");
  }

  @Test
  void theSummarizeSuccessFixtureMapsToAGeneratedSummary() throws IOException {
    server.replyWith(200, fixture("response.summarize-success.json"));

    var outcome =
        (SummaryGenerator.SummaryOutcome.Generated)
            new DefaultSummaryGenerator(invoker())
                .generate(
                    new SummaryGenerator.Request(
                        "The EU adopted the AI Act today.",
                        List.of(
                            new SourceReference("Example Newswire", "https://ex.test/eu-ai-act"))));

    assertThat(outcome.summaryText()).isNotBlank();
    assertThat(outcome.groundingNotes()).isNotBlank();
    assertThat(server.lastRequest().path()).isEqualTo("/capabilities/summarize/v1");
  }
}
