package org.signalengine.infrastructure.rag.indexing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the RAG indexing pipeline (docs/adr/0012-pgvector-persistence-and-indexing.md,
 * docs/adr/0018-pipeline-reliability.md). The reliability values are provisional defaults open to
 * tuning, like the other retry and timeout values (docs/03-technical-spec.md Section 24, T9).
 *
 * @param chunker which chunker the pipeline uses &mdash; {@code "structure-aware"} (the
 *     dependency-free deterministic default) or {@code "semantic"} (needs a configured embedding /
 *     boundary provider). The choice is recorded on every {@code IndexingReport} regardless.
 * @param claimLease how long a run holds the exclusive right to index a content before another run
 *     may take it over. Correctness does not depend on it (a superseded run is rejected when it
 *     stores its result), so it only trades how fast a crashed run is recovered against how often a
 *     slow run is superseded.
 * @param maxFailedAttempts how many consecutive failed attempts a content gets before
 *     reconciliation stops retrying it
 * @param reconciliationBatchLimit how many contents one reconciliation sweep indexes at most
 * @param timeBudget how long the indexing of one content may keep starting AI calls
 * @param maxTimeBudgetOverruns how often a content may exceed that budget before it is blocked
 */
@ConfigurationProperties("signal-engine.rag.indexing")
public record RagIndexingProperties(
    @DefaultValue("structure-aware") String chunker,
    @DefaultValue("40m") Duration claimLease,
    @DefaultValue("5") int maxFailedAttempts,
    @DefaultValue("20") int reconciliationBatchLimit,
    @DefaultValue("30m") Duration timeBudget,
    @DefaultValue("3") int maxTimeBudgetOverruns) {}
