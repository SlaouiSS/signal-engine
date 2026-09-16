package org.signalengine.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.signalengine.infrastructure.rag.generation.AiCapabilityAnswerGenerator;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.context.ContextPassage;
import org.signalengine.rag.generation.GroundingAnswerValidator;
import org.signalengine.rag.generation.RagAnswer;
import org.signalengine.rag.provenance.Citation;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;

/**
 * Opt-in manual validation of the {@code answer} capability against a real running LLM
 * (docs/adr/0015-rag-grounded-generator.md, Step 17). It never runs in the normal suite: it is
 * gated on {@code RAG_ANSWER_REALMODEL=true} and needs the Python AI service reachable at {@code
 * RAG_AGENTS_BASE_URL} (default {@code http://127.0.0.1:8100}) with a chat model configured. No
 * secret is required or read here.
 *
 * <p>It builds the same {@link AiCapabilityAnswerGenerator} and {@link GroundingAnswerValidator}
 * the application wires. Assertions check structure (grounded / not grounded, citations resolve to
 * supplied passages), never wording.
 */
@EnabledIfEnvironmentVariable(named = "RAG_ANSWER_REALMODEL", matches = "true")
class AnswerGeneratorRealModelManualTest {

  private static final HttpAiCapabilityInvoker INVOKER = buildInvoker();
  private final AiCapabilityAnswerGenerator generator = new AiCapabilityAnswerGenerator(INVOKER);
  private final GroundingAnswerValidator validator = new GroundingAnswerValidator();

  private static HttpAiCapabilityInvoker buildInvoker() {
    URI baseUrl =
        URI.create(System.getenv().getOrDefault("RAG_AGENTS_BASE_URL", "http://127.0.0.1:8100"));
    return new HttpAiCapabilityInvoker(
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
        new AiFoundationProperties(baseUrl, Duration.ofSeconds(180), Duration.ofSeconds(5)));
  }

  private static ContextPassage passage(String id, String title, String text) {
    Provenance provenance =
        new Provenance(
            "src-" + id,
            URI.create("https://example.test/" + id),
            title,
            "doc-" + id,
            id,
            Map.of());
    return new ContextPassage(id, text, provenance, Map.of());
  }

  private RagAnswer answer(Query query, Context context) {
    return validator.validate(query, context, generator.generate(query, context));
  }

  @Test
  void answersAQuestionThatTheContextSupports() {
    Context context =
        Context.of(
            List.of(
                passage(
                    "acq::0",
                    "Acme to buy Beta",
                    "Acme Corp said it will acquire Beta Ltd for 1.2 billion euros, with the deal"
                        + " expected to close in Q3."),
                passage(
                    "acq::1",
                    "About Beta",
                    "Beta Ltd makes industrial sensors and employs about 400 people.")));

    RagAnswer result =
        answer(Query.of("How much is Acme paying for Beta and when does it close?"), context);

    assertThat(result.answered()).isTrue();
    assertThat(result.citations()).isNotEmpty();
    assertThat(result.citations())
        .extracting(Citation::passageId)
        .allMatch(id -> id.startsWith("acq::"));
  }

  @Test
  void refusesAQuestionThatTheContextDoesNotSupport() {
    Context context =
        Context.of(
            List.of(
                passage(
                    "acq::0",
                    "Acme to buy Beta",
                    "Acme Corp will acquire Beta Ltd for 1.2 billion euros.")));

    RagAnswer result = answer(Query.of("Who is the chief executive of Acme Corp?"), context);

    assertThat(result.answered()).isFalse();
    assertThat(result.citations()).isEmpty();
  }

  @Test
  void everyKeptCitationResolvesToASuppliedPassageEvenWhenPassagesConflict() {
    Context context =
        Context.of(
            List.of(
                passage("price::0", "Report A", "Acme will pay 1.2 billion euros for Beta."),
                passage("price::1", "Report B", "Acme will pay 1.5 billion euros for Beta.")));

    RagAnswer result = answer(Query.of("How much is Acme paying for Beta?"), context);

    assertThat(result.citations())
        .extracting(Citation::passageId)
        .allMatch(id -> id.startsWith("price::"));
  }
}
