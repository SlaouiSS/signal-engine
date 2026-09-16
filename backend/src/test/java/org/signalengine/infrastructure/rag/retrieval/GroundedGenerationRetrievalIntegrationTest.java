package org.signalengine.infrastructure.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiResponseMetadata;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.infrastructure.rag.generation.AiCapabilityAnswerGenerator;
import org.signalengine.infrastructure.rag.generation.AiCapabilityAnswerGenerator.CitationPayload;
import org.signalengine.infrastructure.rag.generation.AiCapabilityAnswerGenerator.RequestPayload;
import org.signalengine.infrastructure.rag.generation.AiCapabilityAnswerGenerator.ResultPayload;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.context.BudgetedContextAssembler;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.generation.GroundingAnswerValidator;
import org.signalengine.rag.generation.RagAnswer;
import org.signalengine.rag.indexing.IndexedPassage;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.pipeline.StagedRagPipeline;
import org.signalengine.rag.provenance.Citation;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * End-to-end {@code Query -> PgVectorRetriever -> BudgetedContextAssembler ->
 * AiCapabilityAnswerGenerator -> GroundingAnswerValidator -> RagAnswer} against real PostgreSQL +
 * pgvector. No real LLM: the {@code answer} capability is a deterministic in-test {@link
 * AiCapabilityInvoker} that echoes a grounded answer citing the top retrieved passage, so the whole
 * wiring — retrieval provenance into citations, structural validation, no database type in {@link
 * RagAnswer} — is exercised.
 */
class GroundedGenerationRetrievalIntegrationTest extends AbstractPersistenceIntegrationTest {

  private static final int DIM = 768;
  private static final EmbeddingModelDescriptor GEMMA =
      new EmbeddingModelDescriptor("ollama", "embeddinggemma", "embed/v1", DIM);
  private static final ComponentDescriptor CHUNKER =
      new ComponentDescriptor(RagComponentType.CHUNKER, "structure-aware-chunker", "1");

  @Autowired private IndexedPassageStore store;
  @Autowired private JdbcClient jdbcClient;

  private static float[] axis(int index, float value) {
    float[] vector = new float[DIM];
    vector[index] = value;
    return vector;
  }

  private static float[] plane(int a1, float v1, int a2, float v2) {
    float[] vector = new float[DIM];
    vector[a1] = v1;
    vector[a2] = v2;
    return vector;
  }

  private EmbeddingModel stubEmbeddingQueryAs(float[] queryVector) {
    return request -> new EmbeddingResult(List.of(queryVector.clone()), queryVector.length, GEMMA);
  }

  private void index(String id, String contentId, String body, float[] vector) {
    Provenance provenance =
        new Provenance(
            "src-" + id,
            URI.create("https://example.test/a/" + id),
            "Article " + id,
            "doc-" + contentId,
            id,
            Map.of("author", "A. Writer"));
    Passage passage = new Passage(id, 0, body, provenance, Map.of("language", "en"));
    store.save(new IndexedPassage(contentId, passage, CHUNKER, vector, GEMMA));
  }

  /**
   * A deterministic stand-in for the {@code answer} capability: cite the first supplied passage.
   */
  private static AiCapabilityInvoker citingFirstPassage() {
    return new AiCapabilityInvoker() {
      @Override
      @SuppressWarnings("unchecked")
      public <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> resultType) {
        RequestPayload payload = (RequestPayload) request.payload();
        String firstPassageId = payload.passages().get(0).passageId();
        ResultPayload result =
            new ResultPayload(
                true,
                "According to the retrieved context: " + payload.passages().get(0).text(),
                List.of(new CitationPayload(firstPassageId)));
        return (AiCapabilityOutcome<R>)
            new AiCapabilityOutcome.Produced<>(
                result,
                new AiResponseMetadata("test", "deterministic", "answer/v1", 7L),
                UUID.randomUUID());
      }
    };
  }

  @Test
  void groundedAnswerFlowsFromRetrievalThroughGenerationWithProvenancePreserved() {
    String content = "gen-flow";
    index(content + "-A", content, "Acme will acquire Beta for 1.2 billion euros.", axis(0, 1f));
    index(content + "-B", content, "Beta makes industrial sensors.", plane(0, 0.4f, 1, 0.9f));

    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(new PgVectorRetriever(stubEmbeddingQueryAs(axis(0, 1f)), jdbcClient))
            .contextAssembler(new BudgetedContextAssembler())
            .generator(new AiCapabilityAnswerGenerator(citingFirstPassage()))
            .answerValidator(new GroundingAnswerValidator())
            .build();

    RagExecution execution =
        pipeline.execute(
            Query.of("what is Acme buying?", 5).withMetadataFilter("contentId", content));

    RagAnswer answer = execution.answer();
    assertThat(answer.answered()).isTrue();
    assertThat(answer.text()).contains("1.2 billion euros");

    Citation citation = answer.citations().get(0);
    assertThat(citation.passageId()).isEqualTo(content + "-A");
    assertThat(citation.provenance())
        .isEqualTo(execution.retrieval().passages().get(0).provenance());
    assertThat(citation.provenance().sourceId()).isEqualTo("src-" + content + "-A");
    assertThat(citation.provenance().originUri())
        .isEqualTo(URI.create("https://example.test/a/" + content + "-A"));

    assertThat(answer.metadata())
        .containsEntry(GroundingAnswerValidator.META_VALIDATOR, "grounding-answer-validator")
        .containsEntry(GroundingAnswerValidator.META_DOWNGRADED, "false");
    assertThat(execution.stages())
        .extracting(stage -> stage.component().type())
        .containsExactly(
            RagComponentType.RETRIEVER,
            RagComponentType.CONTEXT_ASSEMBLER,
            RagComponentType.GENERATOR,
            RagComponentType.ANSWER_VALIDATOR);

    for (RecordComponent component : Citation.class.getRecordComponents()) {
      assertThat(component.getType().getName()).matches("java\\..*|org\\.signalengine\\.rag\\..*");
    }
  }

  @Test
  void anEmptyRetrievalYieldsAnInsufficientEvidenceAnswerWithoutCallingTheCapability() {
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(new PgVectorRetriever(stubEmbeddingQueryAs(axis(0, 1f)), jdbcClient))
            .contextAssembler(new BudgetedContextAssembler())
            .generator(new AiCapabilityAnswerGenerator(failIfCalled()))
            .answerValidator(new GroundingAnswerValidator())
            .build();

    RagExecution execution =
        pipeline.execute(
            Query.of("anything", 5).withMetadataFilter("contentId", "content-never-indexed"));

    assertThat(execution.retrieval().isEmpty()).isTrue();
    assertThat(execution.answer().answered()).isFalse();
    assertThat(execution.answer().citations()).isEmpty();
  }

  private static AiCapabilityInvoker failIfCalled() {
    return new AiCapabilityInvoker() {
      @Override
      public <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> resultType) {
        throw new AssertionError("the answer capability must not be called for an empty context");
      }
    };
  }
}
