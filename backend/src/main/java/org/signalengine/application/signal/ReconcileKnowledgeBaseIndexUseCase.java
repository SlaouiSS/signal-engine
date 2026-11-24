package org.signalengine.application.signal;

import java.util.List;

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
   * Indexes one bounded page of items that are not completely and currently indexed. Items that
   * keep failing are attempted a bounded number of times. The sweep stops at the first {@link
   * IndexReconciliationReport.Outcome#SUSPENDED} report, because a suspended capability would fail
   * every remaining item the same way.
   */
  List<IndexReconciliationReport> reconcilePending();
}
