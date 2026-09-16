package org.signalengine.rag.embedding;

/**
 * Whether a text is being embedded as a search <b>query</b> or as an indexed <b>passage</b>.
 *
 * <p>Some embedding models are asymmetric: they document a different instruction or prefix for a
 * query than for a document. The core does not know those details &mdash; it only states the role,
 * and the concrete {@link EmbeddingModel} (or the runtime behind it) applies whatever the model
 * requires. A symmetric model ignores the distinction.
 */
public enum TextRole {
  QUERY,
  PASSAGE
}
