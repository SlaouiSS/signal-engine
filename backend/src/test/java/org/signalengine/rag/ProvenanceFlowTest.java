package org.signalengine.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.signalengine.rag.RagCoreDoubles.FirstPassageGenerator;
import org.signalengine.rag.RagCoreDoubles.FixedRetriever;
import org.signalengine.rag.RagCoreDoubles.OrderPreservingContextAssembler;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.pipeline.StagedRagPipeline;
import org.signalengine.rag.provenance.Citation;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;

/** Provenance attached at retrieval survives context assembly and reaches the answer citations. */
class ProvenanceFlowTest {

  @Test
  void provenanceIsCarriedUnchangedFromRetrievalThroughContextToCitation() {
    Provenance retrievedProvenance =
        new Provenance(
            "reuters",
            java.net.URI.create("https://example.test/article-42"),
            "Article 42",
            "doc-42",
            "passage-42-3",
            java.util.Map.of("publishedAt", "2026-09-01"));

    FixedRetriever retriever =
        new FixedRetriever(
            new org.signalengine.rag.retrieval.RetrievedPassage(
                "passage-42-3", "the passage body", retrievedProvenance, 0.77, java.util.Map.of()));

    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(new OrderPreservingContextAssembler())
            .generator(new FirstPassageGenerator())
            .build();

    RagExecution execution = pipeline.execute(Query.of("tell me about article 42"));

    Provenance inRetrieval = execution.retrieval().passages().get(0).provenance();
    Provenance inContext = execution.context().passages().get(0).provenance();
    assertThat(inContext).isEqualTo(inRetrieval).isEqualTo(retrievedProvenance);

    Citation citation = execution.answer().citations().get(0);
    assertThat(citation.passageId()).isEqualTo("passage-42-3");
    assertThat(citation.provenance()).isEqualTo(retrievedProvenance);
    assertThat(citation.provenance().originUri())
        .isEqualTo(java.net.URI.create("https://example.test/article-42"));
    assertThat(citation.provenance().attributes()).containsEntry("publishedAt", "2026-09-01");
  }
}
