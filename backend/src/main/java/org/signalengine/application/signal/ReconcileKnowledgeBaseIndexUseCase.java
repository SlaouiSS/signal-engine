package org.signalengine.application.signal;

import java.util.List;
import org.signalengine.application.pipeline.StageBudget;

/**
 * Input port: bring the knowledge base index back in line with the items whose relevance was
 * confirmed (docs/adr/0018-pipeline-reliability.md).
 *
 * <p>Indexing is a best-effort step after relevance assessment, so it can be lost — the embedding
 * provider was down, the process stopped, an earlier indexing was only partial. An item whose
 * relevance is confirmed but that has no <em>completion marker</em> matching its content and the
 * current chunker configuration is not assumed to be indexed: it is indexed again. Re-indexing is
 * idempotent and atomic, so repeating it never duplicates a passage.
 */
public interface ReconcileKnowledgeBaseIndexUseCase {

  /**
   * Indexes one bounded page of items that are not completely and currently indexed, starting no
   * further item once {@code budget} is exhausted. Items that keep failing are attempted a bounded
   * number of times; a suspended capability leaves an item untouched and does not stop the sweep.
   */
  List<IndexReconciliationReport> reconcilePending(StageBudget budget);

  default List<IndexReconciliationReport> reconcilePending() {
    return reconcilePending(StageBudget.unlimited());
  }
}
