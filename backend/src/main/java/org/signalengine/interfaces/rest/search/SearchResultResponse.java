package org.signalengine.interfaces.rest.search;

import org.signalengine.rag.retrieval.RetrievedPassage;

/** API representation of one retrieved passage (docs/02-functional-spec.md Section 12.3, W9). */
public record SearchResultResponse(
    String passageId, String text, double score, ProvenanceResponse provenance) {

  public static SearchResultResponse from(RetrievedPassage passage) {
    return new SearchResultResponse(
        passage.passageId(),
        passage.text(),
        passage.score(),
        ProvenanceResponse.from(passage.provenance()));
  }
}
