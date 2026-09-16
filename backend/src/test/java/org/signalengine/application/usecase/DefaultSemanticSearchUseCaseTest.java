package org.signalengine.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.signalengine.application.InvalidInputException;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.signalengine.rag.retrieval.RetrievedPassage;
import org.signalengine.rag.retrieval.Retriever;

class DefaultSemanticSearchUseCaseTest {

  private final Retriever retriever = mock(Retriever.class);
  private final DefaultSemanticSearchUseCase useCase = new DefaultSemanticSearchUseCase(retriever);

  @Test
  void delegatesToTheRetrieverWithTheGivenTextAndTopK() {
    RetrievedPassage passage =
        new RetrievedPassage("p1", "passage text", Provenance.ofSource("feed-1"), 0.9, Map.of());
    when(retriever.retrieve(any())).thenReturn(RetrievalResult.of(List.of(passage)));

    RetrievalResult result = useCase.search("acme earnings", 3);

    assertThat(result.passages()).containsExactly(passage);
    verify(retriever).retrieve(Query.of("acme earnings", 3));
  }

  @Test
  void returnsAnEmptyResultWhenNothingMatches() {
    when(retriever.retrieve(any())).thenReturn(RetrievalResult.of(List.of()));

    RetrievalResult result = useCase.search("no matches for this", 5);

    assertThat(result.isEmpty()).isTrue();
  }

  @Test
  void rejectsABlankQuery() {
    assertThatThrownBy(() -> useCase.search("   ", 5)).isInstanceOf(InvalidInputException.class);
  }

  @Test
  void propagatesRetrieverFailures() {
    when(retriever.retrieve(any()))
        .thenThrow(new org.signalengine.rag.embedding.EmbeddingException("provider unreachable"));

    assertThatThrownBy(() -> useCase.search("acme earnings", 5))
        .isInstanceOf(org.signalengine.rag.embedding.EmbeddingException.class);
  }

  @Test
  void preservesProvenanceAndScoreForEachResult() {
    Provenance provenance =
        new Provenance(
            "feed-1", URI.create("https://example.test/a"), "Title", "doc-1", "p1", Map.of());
    RetrievedPassage passage = new RetrievedPassage("p1", "text", provenance, 0.75, Map.of());
    when(retriever.retrieve(any())).thenReturn(RetrievalResult.of(List.of(passage)));

    RetrievalResult result = useCase.search("query", 5);

    RetrievedPassage returned = result.passages().get(0);
    assertThat(returned.passageId()).isEqualTo("p1");
    assertThat(returned.score()).isEqualTo(0.75);
    assertThat(returned.provenance()).isEqualTo(provenance);
  }
}
