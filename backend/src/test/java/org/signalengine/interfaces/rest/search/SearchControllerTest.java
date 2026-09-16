package org.signalengine.interfaces.rest.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.usecase.SemanticSearchUseCase;
import org.signalengine.rag.embedding.EmbeddingException;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.signalengine.rag.retrieval.RetrievedPassage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SearchController.class)
@ActiveProfiles("test")
class SearchControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private SemanticSearchUseCase semanticSearch;

  @Test
  void returnsRankedResultsForAValidQuery() throws Exception {
    Provenance provenance =
        new Provenance(
            "feed-1", URI.create("https://example.test/a"), "Title", "doc-1", "p1", Map.of());
    RetrievedPassage passage =
        new RetrievedPassage("p1", "passage text", provenance, 0.87, Map.of());
    when(semanticSearch.search("acme earnings", 5))
        .thenReturn(RetrievalResult.of(List.of(passage)));

    mockMvc
        .perform(
            post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"query":"acme earnings"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].passageId").value("p1"))
        .andExpect(jsonPath("$.results[0].text").value("passage text"))
        .andExpect(jsonPath("$.results[0].score").value(0.87))
        .andExpect(jsonPath("$.results[0].provenance.sourceId").value("feed-1"))
        .andExpect(jsonPath("$.results[0].provenance.originUri").value("https://example.test/a"));
  }

  @Test
  void appliesTheRequestedTopK() throws Exception {
    when(semanticSearch.search(eq("acme earnings"), eq(10)))
        .thenReturn(RetrievalResult.of(List.of()));

    mockMvc
        .perform(
            post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"query":"acme earnings","topK":10}"""))
        .andExpect(status().isOk());

    verify(semanticSearch).search("acme earnings", 10);
  }

  @Test
  void returnsAnEmptyResultsListWhenNothingMatches() throws Exception {
    when(semanticSearch.search(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(RetrievalResult.of(List.of()));

    mockMvc
        .perform(
            post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"query":"no matches for this"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results").isArray())
        .andExpect(jsonPath("$.results").isEmpty());
  }

  @Test
  void rejectsABlankQueryWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"query":""}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    verify(semanticSearch, never()).search(any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void mapsRetrievalFailureToServiceUnavailable() throws Exception {
    when(semanticSearch.search(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(new EmbeddingException("provider unreachable"));

    mockMvc
        .perform(
            post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"query":"acme earnings"}"""))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("AI_CAPABILITY_UNAVAILABLE"));
  }

  @Test
  void mapsInvalidInputToBadRequest() throws Exception {
    when(semanticSearch.search(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(new InvalidInputException("search query must not be blank"));

    mockMvc
        .perform(
            post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"query":"x"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }
}
