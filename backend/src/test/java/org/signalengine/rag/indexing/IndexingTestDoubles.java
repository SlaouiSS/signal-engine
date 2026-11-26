package org.signalengine.rag.indexing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Chunker;
import org.signalengine.rag.chunking.Chunking;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.provenance.Provenance;

/** Deterministic, dependency-free doubles for the indexing pipeline. No database, no model. */
final class IndexingTestDoubles {

  static final EmbeddingModelDescriptor MODEL_768 =
      new EmbeddingModelDescriptor("fake", "fake-embed", "v1", 4);

  private IndexingTestDoubles() {}

  static IndexableContent content(String contentId, String text) {
    return new IndexableContent(
        contentId,
        text,
        new Provenance("src-1", null, "Title", "doc-1", null, Map.of("k", "v")),
        Map.of("language", "en"));
  }

  static Passage passage(String contentId, int ordinal, String text) {
    return passage(contentId, ordinal, text, "1");
  }

  static Passage passage(String contentId, int ordinal, String text, String chunkerVersion) {
    String id =
        "p-"
            + contentId
            + "-"
            + chunkerVersion
            + "-"
            + ordinal
            + "-"
            + Integer.toHexString(text.hashCode());
    return new Passage(
        id, ordinal, text, new Provenance("src-1", null, "Title", "doc-1", id, Map.of()), Map.of());
  }

  /** A chunker that splits on blank lines into fixed passages, all under one descriptor. */
  static final class SplittingChunker implements Chunker {

    private final ComponentDescriptor descriptor;
    private final String version;

    SplittingChunker(String version) {
      this.version = version;
      this.descriptor = new ComponentDescriptor(RagComponentType.CHUNKER, "splitting", version);
    }

    @Override
    public ComponentDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public Chunking chunk(IndexableContent content) {
      List<Passage> passages = new ArrayList<>();
      String[] blocks = content.text().split("\\R\\R");
      for (int i = 0; i < blocks.length; i++) {
        if (!blocks[i].isBlank()) {
          passages.add(passage(content.contentId(), i, blocks[i].strip(), version));
        }
      }
      return new Chunking(content.contentId(), passages, descriptor, List.of(), Map.of());
    }
  }

  /** An embedding model that returns a deterministic 4-d vector per text. */
  static final class DeterministicEmbeddingModel implements EmbeddingModel {

    boolean fail;
    int calls;

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
      calls++;
      if (fail) {
        throw new org.signalengine.rag.embedding.EmbeddingException(
            "embedding provider unavailable");
      }
      List<float[]> vectors = new ArrayList<>();
      for (String text : request.texts()) {
        int h = text.hashCode();
        vectors.add(
            new float[] {
              (h & 0xFF) / 255f, ((h >> 8) & 0xFF) / 255f, ((h >> 16) & 0xFF) / 255f, 0.5f
            });
      }
      return new EmbeddingResult(vectors, 4, MODEL_768);
    }
  }

  /**
   * An in-memory store keyed by (passageId, embeddingModel) — proves idempotency — with the same
   * claim, atomic-replace and fencing contract as the real one.
   */
  static final class InMemoryIndexedPassageStore implements IndexedPassageStore {

    final Map<String, IndexedPassage> entries = new LinkedHashMap<>();
    final Map<String, Long> revisionByContent = new LinkedHashMap<>();
    final Set<String> claimedContents = new HashSet<>();
    final List<String> abandonedContents = new ArrayList<>();
    final List<AbandonedIndexing.Cause> abandonmentCauses = new ArrayList<>();
    final Map<String, String> completionMarkers = new LinkedHashMap<>();
    final Map<String, ComponentDescriptor> markerChunkers = new LinkedHashMap<>();
    boolean failOnReplace;

    /** Simulates a newer run claiming the content between this run's claim and its replace. */
    boolean supersedeBeforeReplace;

    @Override
    public Optional<IndexClaim> claim(
        String contentId, String contentFingerprint, ComponentDescriptor chunker) {
      boolean alreadyCurrent =
          contentFingerprint.equals(completionMarkers.get(contentId))
              && chunker.equals(markerChunkers.get(contentId));
      if (alreadyCurrent || !claimedContents.add(contentId)) {
        return Optional.empty();
      }
      long revision = revisionByContent.merge(contentId, 1L, Long::sum);
      return Optional.of(new IndexClaim(contentId, revision));
    }

    @Override
    public ContentReplacement replaceContent(
        IndexClaim claim, String contentFingerprint, List<IndexedPassage> passages) {
      if (supersedeBeforeReplace) {
        revisionByContent.merge(claim.contentId(), 1L, Long::sum);
      }
      if (revisionByContent.get(claim.contentId()) != claim.revision()) {
        throw new StaleIndexClaimException(claim);
      }
      if (failOnReplace) {
        throw new IllegalStateException("simulated persistence failure");
      }
      IndexedPassage first = passages.get(0);
      Set<String> keptPassageIds = new HashSet<>();
      int inserted = 0;
      int updated = 0;
      for (IndexedPassage indexed : passages) {
        keptPassageIds.add(indexed.passageId());
        String key = indexed.passageId() + "|" + indexed.embeddingModel().model();
        if (entries.put(key, indexed) == null) {
          inserted++;
        } else {
          updated++;
        }
      }
      int before = entries.size();
      entries
          .values()
          .removeIf(
              existing ->
                  existing.contentId().equals(claim.contentId())
                      && existing.chunker().equals(first.chunker())
                      && !keptPassageIds.contains(existing.passageId()));
      claimedContents.remove(claim.contentId());
      completionMarkers.put(claim.contentId(), contentFingerprint);
      markerChunkers.put(claim.contentId(), first.chunker());
      return new ContentReplacement(inserted, updated, before - entries.size());
    }

    @Override
    public void abandon(IndexClaim claim, AbandonedIndexing abandonment) {
      claimedContents.remove(claim.contentId());
      abandonedContents.add(claim.contentId());
      abandonmentCauses.add(abandonment.cause());
    }
  }
}
