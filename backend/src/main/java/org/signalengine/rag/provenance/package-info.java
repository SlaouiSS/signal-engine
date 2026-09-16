/**
 * Generic provenance and citation representation for the RAG core (docs/07-rag.md Section 11, 21).
 *
 * <p>Provenance is mandatory throughout the RAG contracts: every {@link
 * org.signalengine.rag.retrieval.RetrievedPassage} carries a {@link
 * org.signalengine.rag.provenance.Provenance}, it survives unchanged into the assembled {@link
 * org.signalengine.rag.context.Context}, and a {@link org.signalengine.rag.generation.Generator}
 * references it from each {@link org.signalengine.rag.provenance.Citation}.
 *
 * <p>{@code Provenance} is deliberately not tied to Signal Engine's {@code Source} entity or to any
 * other business type. It records only what a citation needs: a source identifier, the original URL
 * when there is one, a title, the document and passage identifiers, and an open map for any further
 * citation metadata a concrete deployment needs. No other fields are invented here.
 */
package org.signalengine.rag.provenance;
