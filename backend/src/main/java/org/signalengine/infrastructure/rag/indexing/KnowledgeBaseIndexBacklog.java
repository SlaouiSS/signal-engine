package org.signalengine.infrastructure.rag.indexing;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.chunking.Chunker;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Which raw information items wait for indexing in the knowledge base: relevance confirmed, but no
 * completion marker in {@code rag_content_index} (migration V13) that is complete and current
 * (docs/adr/0018-pipeline-reliability.md).
 *
 * <p>It lives here, next to {@link SignalEngineKnowledgeBaseIndexer} and {@code
 * SignalEngineIndexableContent}, because this is where Signal Engine's ingestion data meets the
 * knowledge base's tables: the content id of an indexed item is the raw information item id, and
 * what "relevance confirmed" means in terms of processing state is read here, so neither the
 * ingestion repository nor the generic RAG core has to know about the other's schema.
 *
 * <p>An item is <em>current</em> when its marker was built from the item's content hash and the
 * configured chunker configuration. A change of embedding model is not detected here; it belongs to
 * the open re-embedding procedure (T3).
 */
class KnowledgeBaseIndexBacklog {

  /**
   * Relevance is confirmed in these processing states, and in {@code failed} when the failure came
   * after relevance assessment (a failed relevance assessment, or an item not yet grouped, is not
   * retained knowledge).
   */
  private static final String FIND_ITEM_IDS_AWAITING_INDEXING =
      """
      SELECT r.id
      FROM raw_information_item r
      LEFT JOIN rag_content_index m ON m.content_id = CAST(r.id AS text)
      WHERE r.relevant_information_id IS NOT NULL
        AND r.normalized_content IS NOT NULL
        AND (r.processing_state IN ('relevant', 'signal_created', 'no_signal', 'summarized')
             OR (r.processing_state = 'failed'
                 AND COALESCE(r.failed_stage, '') <> 'relevance_assessment'))
        AND (m.content_id IS NULL
             OR (m.failed_attempts < :maxFailedAttempts
                 AND (m.lease_until IS NULL OR m.lease_until <= :now)
                 AND (m.indexed_at IS NULL
                      OR m.content_fingerprint <> r.content_hash
                      OR m.chunker_id <> :chunkerId
                      OR m.chunker_version <> :chunkerVersion)))
      ORDER BY COALESCE(m.updated_at, r.processing_updated_at) ASC, r.id ASC
      LIMIT :limit
      """;

  private final JdbcClient jdbcClient;
  private final ComponentDescriptor chunker;

  KnowledgeBaseIndexBacklog(JdbcClient jdbcClient, Chunker chunker) {
    this.jdbcClient = jdbcClient;
    this.chunker = chunker.descriptor();
  }

  List<UUID> findItemIdsAwaitingIndexing(Instant now, int maxFailedAttempts, int limit) {
    return jdbcClient
        .sql(FIND_ITEM_IDS_AWAITING_INDEXING)
        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        .param("maxFailedAttempts", maxFailedAttempts)
        .param("chunkerId", chunker.implementationId())
        .param("chunkerVersion", chunker.version())
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }
}
