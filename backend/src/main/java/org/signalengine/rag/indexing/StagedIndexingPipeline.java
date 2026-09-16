package org.signalengine.rag.indexing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Chunker;
import org.signalengine.rag.chunking.Chunking;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;

/**
 * The supplied {@link IndexingPipeline}: the fixed offline flow
 *
 * <pre>
 *   IndexableContent → Chunker → passages → EmbeddingModel → IndexedPassageStore → IndexingReport
 * </pre>
 *
 * composed from three replaceable collaborators. It holds no chunking, embedding or persistence
 * logic of its own; each is a contract. Not subclassed, not a god-object &mdash; the counterpart of
 * {@code StagedRagPipeline} on the indexing side.
 *
 * <p>Deterministic and idempotent: passage identity comes from {@link
 * org.signalengine.rag.chunking.PassageIds}, and the store keys on it, so re-running over the same
 * content and configuration updates in place rather than duplicating.
 *
 * <p>Error handling is explicit. An embedding failure aborts the content ({@link
 * org.signalengine.rag.embedding.EmbeddingException} propagates, nothing is persisted). A contract
 * violation (vector count or dimension) throws {@link IndexingException}. A per-passage persistence
 * failure is counted in {@link IndexingReport#failed()} and described in {@link
 * IndexingReport#notes()}; the remaining passages are still attempted. Nothing is swallowed.
 */
public final class StagedIndexingPipeline implements IndexingPipeline {

  private static final ComponentDescriptor DESCRIPTOR =
      new ComponentDescriptor(
          RagComponentType.INDEXING_PIPELINE, "staged-indexing-pipeline", "staged/v1");

  private final Chunker chunker;
  private final EmbeddingModel embeddingModel;
  private final IndexedPassageStore store;

  private StagedIndexingPipeline(Builder builder) {
    this.chunker = Objects.requireNonNull(builder.chunker, "chunker is required");
    this.embeddingModel =
        Objects.requireNonNull(builder.embeddingModel, "embeddingModel is required");
    this.store = Objects.requireNonNull(builder.store, "store is required");
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public ComponentDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public IndexingReport index(IndexableContent content) {
    Objects.requireNonNull(content, "content");

    Chunking chunking = chunker.chunk(content);
    List<Passage> passages = chunking.passages();

    Map<String, String> metadata = baseMetadata(chunking);
    if (passages.isEmpty()) {
      return new IndexingReport(
          content.contentId(),
          0,
          0,
          0,
          0,
          0,
          DESCRIPTOR,
          List.of("chunking produced no passages"),
          metadata);
    }

    List<String> texts = passages.stream().map(Passage::text).toList();
    EmbeddingResult embeddings = embeddingModel.embed(EmbeddingRequest.forPassages(texts));

    if (embeddings.count() != passages.size()) {
      throw new IndexingException(
          "embedding returned "
              + embeddings.count()
              + " vectors for "
              + passages.size()
              + " passages");
    }
    if (embeddings.dimension() != embeddings.model().dimension()) {
      throw new IndexingException(
          "embedding dimension "
              + embeddings.dimension()
              + " disagrees with the model descriptor dimension "
              + embeddings.model().dimension());
    }

    int inserted = 0;
    int updated = 0;
    int failed = 0;
    List<String> notes = new ArrayList<>();
    for (int i = 0; i < passages.size(); i++) {
      try {
        IndexedPassage indexed =
            new IndexedPassage(
                content.contentId(),
                passages.get(i),
                chunking.chunker(),
                embeddings.vector(i),
                embeddings.model());
        switch (store.save(indexed)) {
          case INSERTED -> inserted++;
          case UPDATED -> updated++;
        }
      } catch (RuntimeException failure) {
        failed++;
        notes.add(
            "passage " + passages.get(i).passageId() + " not stored: " + failure.getMessage());
      }
    }

    metadata.put("embeddingProvider", embeddings.model().provider());
    metadata.put("embeddingModel", embeddings.model().model());
    metadata.put("embeddingModelVersion", embeddings.model().version());
    metadata.put("embeddingDimension", Integer.toString(embeddings.dimension()));
    metadata.put("inserted", Integer.toString(inserted));
    metadata.put("updated", Integer.toString(updated));

    return new IndexingReport(
        content.contentId(),
        passages.size(),
        embeddings.count(),
        inserted + updated,
        0,
        failed,
        DESCRIPTOR,
        notes,
        metadata);
  }

  private Map<String, String> baseMetadata(Chunking chunking) {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("chunker", chunking.chunker().implementationId());
    metadata.put("chunkerVersion", chunking.chunker().version());
    metadata.put("store", store.descriptor().implementationId());
    return metadata;
  }

  /** Assembles a {@link StagedIndexingPipeline}; all three collaborators are required. */
  public static final class Builder {

    private Chunker chunker;
    private EmbeddingModel embeddingModel;
    private IndexedPassageStore store;

    private Builder() {}

    public Builder chunker(Chunker value) {
      this.chunker = value;
      return this;
    }

    public Builder embeddingModel(EmbeddingModel value) {
      this.embeddingModel = value;
      return this;
    }

    public Builder store(IndexedPassageStore value) {
      this.store = value;
      return this;
    }

    public StagedIndexingPipeline build() {
      return new StagedIndexingPipeline(this);
    }
  }
}
