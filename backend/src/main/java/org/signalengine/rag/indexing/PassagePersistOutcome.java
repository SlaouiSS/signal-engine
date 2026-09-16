package org.signalengine.rag.indexing;

/**
 * What an {@link IndexedPassageStore#save} call did with one {@link IndexedPassage}.
 *
 * <p>Indexing is idempotent by identity, so a repeated call over the same passage and the same
 * embedding-model configuration reports {@link #UPDATED}, never a second stored copy.
 */
public enum PassagePersistOutcome {

  /** The passage/embedding was not stored before and is now. */
  INSERTED,

  /** A row with this logical identity already existed and was refreshed in place. */
  UPDATED
}
