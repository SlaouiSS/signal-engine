package org.signalengine.rag.indexing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Chunker;
import org.signalengine.rag.chunking.Chunking;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;

/**
 * The supplied {@link IndexingPipeline}:
 *
 * <pre>
 *   IndexableContent → claim → Chunker → passages → EmbeddingModel → replace → IndexingReport
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
 * <p>Preparation is outside any transaction, storage is atomic (docs/adr/0018-pipeline-
 * reliability.md). The pipeline takes an {@link IndexClaim}, chunks and embeds with no database
 * transaction open, and then hands every passage to {@link IndexedPassageStore#replaceContent} in
 * one step. So a failure anywhere leaves the previous index of the content untouched, and a run
 * that lost its claim in the meantime writes nothing.
 *
 * <p>Error handling is explicit. A failure while preparing aborts the content: the claim is
 * abandoned (the configured classifier says what the failure costs the content) and the exception
 * propagates &mdash; an {@link org.signalengine.rag.embedding.EmbeddingException} for embedding, an
 * {@link IndexingException} for a contract violation or when chunking produces no passage at all
 * (there is nothing to mark as indexed, so that is never reported as a success). A content already
 * claimed by another run, or whose claim was superseded before the result could be stored, is
 * reported as <em>skipped</em>, not as an error. Nothing is swallowed.
 */
public final class StagedIndexingPipeline implements IndexingPipeline {

  private static final ComponentDescriptor DESCRIPTOR =
      new ComponentDescriptor(
          RagComponentType.INDEXING_PIPELINE, "staged-indexing-pipeline", "staged/v2");

  private final Chunker chunker;
  private final EmbeddingModel embeddingModel;
  private final IndexedPassageStore store;
  private final Function<RuntimeException, AbandonedIndexing> failureClassifier;

  private StagedIndexingPipeline(Builder builder) {
    this.chunker = Objects.requireNonNull(builder.chunker, "chunker is required");
    this.embeddingModel =
        Objects.requireNonNull(builder.embeddingModel, "embeddingModel is required");
    this.store = Objects.requireNonNull(builder.store, "store is required");
    this.failureClassifier = builder.failureClassifier;
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

    Optional<IndexClaim> claim =
        store.claim(content.contentId(), content.fingerprint(), chunker.descriptor());
    if (claim.isEmpty()) {
      return skipped(
          content,
          "nothing to index: another run holds the claim, or the content is already completely"
              + " indexed for this chunker configuration");
    }
    try {
      return indexClaimed(content, claim.get());
    } catch (StaleIndexClaimException superseded) {
      return skipped(content, superseded.getMessage() + "; nothing written");
    } catch (RuntimeException failure) {
      store.abandon(claim.get(), failureClassifier.apply(failure));
      throw failure;
    }
  }

  private IndexingReport indexClaimed(IndexableContent content, IndexClaim claim) {
    Chunking chunking = chunker.chunk(content);
    List<Passage> passages = chunking.passages();

    if (passages.isEmpty()) {
      // No passage means no completion marker can be written, so the content would stay
      // unindexed: that is a failed indexing (counted, hence bounded), never a success.
      throw new IndexingException(
          "chunking produced no passage for content " + content.contentId() + "; nothing to index");
    }
    Map<String, String> metadata = baseMetadata(chunking);

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

    List<IndexedPassage> indexedPassages = new ArrayList<>(passages.size());
    for (int i = 0; i < passages.size(); i++) {
      indexedPassages.add(
          new IndexedPassage(
              content.contentId(),
              passages.get(i),
              chunking.chunker(),
              embeddings.vector(i),
              embeddings.model()));
    }

    ContentReplacement replacement =
        store.replaceContent(claim, content.fingerprint(), indexedPassages);

    metadata.put("embeddingProvider", embeddings.model().provider());
    metadata.put("embeddingModel", embeddings.model().model());
    metadata.put("embeddingModelVersion", embeddings.model().version());
    metadata.put("embeddingDimension", Integer.toString(embeddings.dimension()));
    metadata.put("inserted", Integer.toString(replacement.inserted()));
    metadata.put("updated", Integer.toString(replacement.updated()));
    metadata.put("removedStale", Integer.toString(replacement.removedStale()));

    return new IndexingReport(
        content.contentId(),
        passages.size(),
        embeddings.count(),
        replacement.stored(),
        0,
        0,
        DESCRIPTOR,
        List.of(),
        metadata);
  }

  private IndexingReport skipped(IndexableContent content, String note) {
    return new IndexingReport(
        content.contentId(), 0, 0, 0, 1, 0, DESCRIPTOR, List.of(note), new LinkedHashMap<>());
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
    private Function<RuntimeException, AbandonedIndexing> failureClassifier =
        failure ->
            new AbandonedIndexing(
                AbandonedIndexing.Cause.FAILED_ATTEMPT,
                false,
                String.valueOf(failure.getMessage()));

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

    /**
     * What each failure costs the content (default: every failure is a failed attempt that will not
     * succeed on its own).
     */
    public Builder failureClassifier(Function<RuntimeException, AbandonedIndexing> value) {
      this.failureClassifier = Objects.requireNonNull(value, "classifier must not be null");
      return this;
    }

    public StagedIndexingPipeline build() {
      return new StagedIndexingPipeline(this);
    }
  }
}
