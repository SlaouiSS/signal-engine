package org.signalengine.interfaces.rest.question;

import org.signalengine.interfaces.rest.search.ProvenanceResponse;
import org.signalengine.rag.provenance.Citation;

/**
 * API representation of one citation backing a grounded answer (docs/02-functional-spec.md Section
 * 13.2; docs/07-rag.md Section 11). Always derived from the {@link Citation} the RAG pipeline
 * attached from retrieved context &mdash; never authored by the API.
 */
public record CitationResponse(String passageId, ProvenanceResponse provenance, String quotedText) {

  public static CitationResponse from(Citation citation) {
    return new CitationResponse(
        citation.passageId(),
        ProvenanceResponse.from(citation.provenance()),
        citation.quotedText());
  }
}
