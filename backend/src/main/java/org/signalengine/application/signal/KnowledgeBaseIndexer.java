package org.signalengine.application.signal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;
import org.signalengine.rag.indexing.IndexingReport;

/**
 * Output port: index one raw information item's content into the searchable knowledge base
 * (docs/07-rag.md Section 21.7; docs/05-data-model.md Section 16), and tell which items still wait
 * for it.
 *
 * <p>Called once an item is confirmed relevant and therefore becomes retained knowledge
 * (docs/02-functional-spec.md Section 12.3, workflow W9; docs/08-ingestion.md Section 22 —
 * ingestion decides what belongs in the knowledge base, RAG only makes it searchable). This port
 * performs no chunking, embedding, or persistence of its own; it delegates entirely to the existing
 * {@link org.signalengine.rag.indexing.IndexingPipeline}.
 *
 * <p>Indexing is a best-effort enrichment, not a gate in the {@link
 * org.signalengine.domain.ProcessingState} pipeline: a failure here does not affect the
 * relevance-assessment outcome (see {@link DefaultProcessRelevantInformationUseCase}). Because it
 * can fail, it is also repeatable: calling {@link #index} again for the same item is idempotent and
 * atomic, and {@link ReconcileKnowledgeBaseIndexUseCase} does so for the items {@link
 * #findItemIdsAwaitingIndexing} reports (docs/adr/0018-pipeline-reliability.md).
 *
 * <p>Which items are indexed completely and currently is knowledge of the knowledge base, not of
 * ingestion: the completion markers live with the indexed passages. That is why the question is
 * asked here and not of the raw information item repository.
 */
public interface KnowledgeBaseIndexer {

  /**
   * @param item the raw information item to index; must have normalized content
   * @param source the item's source, for provenance
   * @return what the indexing pipeline did with this content; {@code skipped() > 0} when another
   *     run holds the item or it is already completely indexed, in which case nothing was done
   * @throws org.signalengine.rag.embedding.EmbeddingException if the embedding call failed
   * @throws org.signalengine.rag.indexing.IndexingException if indexing produced no passage, or the
   *     embedding result violated its contract
   * @throws IllegalArgumentException if the item has no normalized content to index
   */
  IndexingReport index(RawInformationItem item, Source source);

  /**
   * A bounded page of the ids of raw information items whose relevance is confirmed but whose
   * indexing is not complete and current: no completion marker, or a marker built from other
   * content or another chunker configuration. Items another run is indexing right now, items
   * waiting for their next attempt, and blocked items are left out. An earlier indexing is never
   * assumed to be complete.
   */
  List<UUID> findItemIdsAwaitingIndexing(Instant now, int limit);

  /** The items whose indexing is no longer attempted, oldest first. */
  List<BlockedIndexing> findBlockedIndexing();

  /**
   * Makes every recoverable blocked indexing eligible again, with its counts cleared, and returns
   * the ids of those items. Repeating the call changes nothing.
   */
  List<UUID> requeueBlockedIndexing();
}
