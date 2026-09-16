package org.signalengine.infrastructure.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.context.BudgetedContextAssembler;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.context.ContextBudget;
import org.signalengine.rag.context.ContextPassage;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.indexing.IndexedPassage;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.pipeline.StagedRagPipeline;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.signalengine.rag.retrieval.RetrievedPassage;
import org.signalengine.rag.retrieval.Retriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * End-to-end {@code Query -> PgVectorRetriever -> BudgetedContextAssembler -> Context} against real
 * PostgreSQL + pgvector: retrieved provenance, ordering and retrieval score survive into the
 * context; topK and the character budget interact; exact-duplicate ids never reach retrieval (the
 * store keys on passage id) so the assembler's duplicate guard is exercised with a crafted result;
 * no database type leaks into {@link Context}.
 */
class ContextAssemblyRetrievalIntegrationTest extends AbstractPersistenceIntegrationTest {

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

  private Retriever retrieverEmbeddingQueryAs(float[] queryVector) {
    EmbeddingModel stub =
        request -> new EmbeddingResult(List.of(queryVector.clone()), queryVector.length, GEMMA);
    return new PgVectorRetriever(stub, jdbcClient);
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

  @Test
  void retrievedProvenanceOrderingAndScoreSurviveIntoTheAssembledContext() {
    String content = "ctx-survives";
    index(content + "-A", content, "closest passage body", axis(0, 1f)); // cos 1.0
    index(content + "-B", content, "middle passage body", plane(0, 0.5f, 1, 0.8660254f)); // cos 0.5
    index(content + "-C", content, "farthest passage body", axis(1, 1f)); // cos 0.0

    var retrieval =
        retrieverEmbeddingQueryAs(axis(0, 1f))
            .retrieve(Query.of("q", 3).withMetadataFilter("contentId", content));

    Context context = new BudgetedContextAssembler().assemble(Query.of("q"), retrieval);

    assertThat(context.passages())
        .extracting(ContextPassage::passageId)
        .containsExactly(content + "-A", content + "-B", content + "-C");

    ContextPassage first = context.passages().get(0);
    RetrievedPassage firstRetrieved = retrieval.passages().get(0);
    assertThat(first.provenance()).isEqualTo(firstRetrieved.provenance());
    assertThat(first.provenance().sourceId()).isEqualTo("src-" + content + "-A");
    assertThat(first.provenance().originUri())
        .isEqualTo(URI.create("https://example.test/a/" + content + "-A"));
    assertThat(first.text()).isEqualTo("closest passage body");
    assertThat(first.metadata())
        .containsEntry(BudgetedContextAssembler.PASSAGE_META_RETRIEVAL_RANK, "0")
        .containsEntry("language", "en");
    assertThat(first.metadata().get(BudgetedContextAssembler.PASSAGE_META_RETRIEVAL_SCORE))
        .isEqualTo(Double.toString(firstRetrieved.score()));
  }

  @Test
  void topKAndTheCharacterBudgetInteract() {
    String content = "ctx-budget";
    for (int i = 0; i < 5; i++) {
      index(content + "-" + i, content, "z".repeat(40), plane(0, 1f, i + 2, 0.05f * (i + 1)));
    }

    // retrieval caps candidates at topK=4; the assembler's 100-char budget then keeps 2 of them.
    var retrieval =
        retrieverEmbeddingQueryAs(axis(0, 1f))
            .retrieve(Query.of("q", 4).withMetadataFilter("contentId", content));
    assertThat(retrieval.passages()).hasSize(4);

    Context context =
        new BudgetedContextAssembler(ContextBudget.ofCharacters(100))
            .assemble(Query.of("q"), retrieval);

    assertThat(context.passages()).hasSize(2);
    assertThat(context.metadata())
        .containsEntry(BudgetedContextAssembler.META_RETRIEVED_PASSAGES, "4")
        .containsEntry(BudgetedContextAssembler.META_SELECTED_PASSAGES, "2")
        .containsEntry(BudgetedContextAssembler.META_SKIPPED_FOR_BUDGET, "2");
  }

  @Test
  void exactDuplicateIdsInARetrievalResultAreCollapsedByTheAssembler() {
    String content = "ctx-dupe";
    index(content + "-A", content, "only body", axis(2, 1f));

    var single =
        retrieverEmbeddingQueryAs(axis(2, 1f))
            .retrieve(Query.of("q", 1).withMetadataFilter("contentId", content));
    var withDuplicate =
        new org.signalengine.rag.retrieval.RetrievalResult(
            List.of(single.passages().get(0), single.passages().get(0)), single.metadata());

    Context context = new BudgetedContextAssembler().assemble(Query.of("q"), withDuplicate);

    assertThat(context.passages()).hasSize(1);
    assertThat(context.metadata())
        .containsEntry(BudgetedContextAssembler.META_DUPLICATE_IDS_REMOVED, "1");
  }

  @Test
  void assembleContextThroughTheStagedPipelineWithTheRealRetriever() {
    String content = "ctx-pipeline";
    index(content + "-A", content, "first body", axis(0, 1f));
    index(content + "-B", content, "second body", axis(1, 1f));

    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retrieverEmbeddingQueryAs(axis(0, 1f)))
            .contextAssembler(new BudgetedContextAssembler())
            .build();

    Context context =
        pipeline.assembleContext(Query.of("q", 5).withMetadataFilter("contentId", content));

    assertThat(context.passages())
        .extracting(ContextPassage::passageId)
        .containsExactly(content + "-A", content + "-B");

    for (RecordComponent component : ContextPassage.class.getRecordComponents()) {
      assertThat(component.getType().getName()).matches("java\\..*|org\\.signalengine\\.rag\\..*");
    }
  }

  @Test
  void assemblyOverARealRetrievalResultIsFastBudgetBoundedAndDeterministic() {
    String content = "ctx-measure";
    for (int i = 0; i < 12; i++) {
      index(
          content + "-" + i,
          content,
          "word ".repeat(80).trim(),
          plane(0, 1f, i + 2, 0.02f * (i + 1)));
    }

    RetrievalResult retrieval =
        retrieverEmbeddingQueryAs(axis(0, 1f))
            .retrieve(Query.of("q", 12).withMetadataFilter("contentId", content));
    assertThat(retrieval.passages()).hasSize(12);

    BudgetedContextAssembler assembler =
        new BudgetedContextAssembler(ContextBudget.ofCharacters(2_000));

    Context firstRun = assembler.assemble(Query.of("q"), retrieval);
    long totalNanos = 0;
    int iterations = 200;
    for (int i = 0; i < iterations; i++) {
      long startedAt = System.nanoTime();
      Context context = assembler.assemble(Query.of("q"), retrieval);
      totalNanos += System.nanoTime() - startedAt;
      assertThat(context).isEqualTo(firstRun); // deterministic
    }

    int usedCharacters =
        firstRun.passages().stream().mapToInt(passage -> passage.text().length()).sum();
    assertThat(usedCharacters).isLessThanOrEqualTo(2_000);
    assertThat(firstRun.passages()).isNotEmpty();
    assertThat(
            firstRun.passages().size()
                + Integer.parseInt(
                    firstRun.metadata().get(BudgetedContextAssembler.META_SKIPPED_FOR_BUDGET)))
        .isEqualTo(12);

    System.out.printf(
        "context-assembly: %d passages retrieved, %d selected, %d chars, ~%.1f us/assembly%n",
        retrieval.passages().size(),
        firstRun.passages().size(),
        usedCharacters,
        totalNanos / 1_000.0 / iterations);
  }
}
