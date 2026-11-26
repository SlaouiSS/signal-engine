package org.signalengine.rag.indexing;

/**
 * What an {@link IndexedPassageStore#replaceContent} call did, atomically, for one content.
 *
 * @param inserted passages (with their embedding) that were not stored before
 * @param updated passages whose logical identity already existed and were refreshed in place
 * @param removedStale passages of the same content and chunker configuration that are no longer
 *     produced by it, removed in the same transaction; never counts another chunker configuration's
 *     passages, which are left alone
 */
public record ContentReplacement(int inserted, int updated, int removedStale) {

  public ContentReplacement {
    if (inserted < 0 || updated < 0 || removedStale < 0) {
      throw new IllegalArgumentException("counts must not be negative");
    }
  }

  /** Passages of the content that are stored after the replacement. */
  public int stored() {
    return inserted + updated;
  }
}
