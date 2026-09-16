package org.signalengine.interfaces.rest.search;

import org.signalengine.rag.provenance.Provenance;

/**
 * API representation of where a search result or citation came from (docs/07-rag.md Section 11).
 * {@code passageId} is intentionally omitted here — it is already the enclosing result's own
 * identifier.
 */
public record ProvenanceResponse(
    String sourceId, String originUri, String title, String documentId) {

  public static ProvenanceResponse from(Provenance provenance) {
    return new ProvenanceResponse(
        provenance.sourceId(),
        provenance.originUri() == null ? null : provenance.originUri().toString(),
        provenance.title(),
        provenance.documentId());
  }
}
