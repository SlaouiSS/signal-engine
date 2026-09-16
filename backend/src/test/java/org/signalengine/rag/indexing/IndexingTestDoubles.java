package org.signalengine.rag.indexing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
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

  /** An in-memory store keyed by (passageId, embeddingModel) — proves idempotency. */
  static final class InMemoryIndexedPassageStore implements IndexedPassageStore {

    final Map<String, IndexedPassage> entries = new LinkedHashMap<>();
    String failForPassageId;

    @Override
    public PassagePersistOutcome save(IndexedPassage indexed) {
      if (indexed.passageId().equals(failForPassageId)) {
        throw new IllegalStateException("simulated persistence failure");
      }
      String key = indexed.passageId() + "|" + indexed.embeddingModel().model();
      PassagePersistOutcome outcome =
          entries.containsKey(key) ? PassagePersistOutcome.UPDATED : PassagePersistOutcome.INSERTED;
      entries.put(key, indexed);
      return outcome;
    }
  }
}
