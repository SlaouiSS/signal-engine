package org.signalengine.infrastructure.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.signalengine.rag.indexing.IndexClaim;
import org.signalengine.rag.indexing.IndexedPassage;
import org.signalengine.rag.indexing.IndexedPassageStore;

/**
 * Test fixture: seeds a real {@link IndexedPassageStore} one passage at a time through the same
 * claim-then-replace path production uses. A replacement is atomic per content, so the fixture
 * remembers the passages already seeded for a content (and embedding model) and replaces with the
 * accumulated set — which is how a test builds a content that has several passages.
 */
public final class SeededPassageIndex {

  private final IndexedPassageStore store;
  private final Map<String, List<IndexedPassage>> passagesByContentAndModel = new HashMap<>();
  private final AtomicInteger sequence = new AtomicInteger();

  public SeededPassageIndex(IndexedPassageStore store) {
    this.store = store;
  }

  /** Adds {@code passage} to its content's seeded passages and replaces the stored set. */
  public void add(IndexedPassage passage) {
    String key = passage.contentId() + "|" + passage.embeddingModel().model();
    List<IndexedPassage> passages =
        passagesByContentAndModel.computeIfAbsent(key, ignored -> new ArrayList<>());
    passages.removeIf(existing -> existing.passageId().equals(passage.passageId()));
    passages.add(passage);

    // A distinct fingerprint per call: seeding a content again is meant to replace it.
    String fingerprint = "seeded-" + passage.contentId() + "-" + sequence.incrementAndGet();
    IndexClaim claim =
        store
            .claim(passage.contentId(), fingerprint, passage.chunker())
            .orElseThrow(
                () -> new IllegalStateException("content " + passage.contentId() + " is claimed"));
    store.replaceContent(claim, fingerprint, List.copyOf(passages));
  }
}
