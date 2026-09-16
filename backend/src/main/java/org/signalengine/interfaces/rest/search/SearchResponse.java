package org.signalengine.interfaces.rest.search;

import java.util.List;
import org.signalengine.rag.retrieval.RetrievalResult;

/**
 * API representation of a semantic-search result (docs/02-functional-spec.md Section 12.3, W9).
 * {@code results} is empty &mdash; not an error &mdash; when nothing matched.
 */
public record SearchResponse(List<SearchResultResponse> results) {

  public static SearchResponse from(RetrievalResult retrievalResult) {
    return new SearchResponse(
        retrievalResult.passages().stream().map(SearchResultResponse::from).toList());
  }
}
