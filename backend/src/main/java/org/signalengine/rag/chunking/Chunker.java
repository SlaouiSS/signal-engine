package org.signalengine.rag.chunking;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.indexing.IndexableContent;

/**
 * Splits one unit of normalized content into ordered {@link Passage passages}.
 *
 * <p>The contract says nothing about <i>how</i>. A deterministic structural split ({@link
 * StructureAwareChunker}), a fixed-size or recursive split, a Markdown- or HTML-aware split, or a
 * language-model-driven semantic split all implement this one method. No implementation's
 * configuration type, and no third-party library type, appears here.
 *
 * <p>Every implementation must:
 *
 * <ul>
 *   <li>reject blank content ({@link IndexableContent} already enforces non-blank text);
 *   <li>return passages in document order, each with non-blank text;
 *   <li>carry the content's {@link org.signalengine.rag.provenance.Provenance} through to every
 *       passage, setting the passage id on it;
 *   <li>give each passage a deterministic id (see {@link PassageIds}) so re-chunking the same
 *       content with the same configuration is idempotent;
 *   <li>report its identity and configuration through {@link #descriptor()} and on the returned
 *       {@link Chunking}.
 * </ul>
 */
@FunctionalInterface
public interface Chunker {

  Chunking chunk(IndexableContent content);

  /** Identity ({@link RagComponentType#CHUNKER}) and configuration version of this chunker. */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.CHUNKER);
  }
}
