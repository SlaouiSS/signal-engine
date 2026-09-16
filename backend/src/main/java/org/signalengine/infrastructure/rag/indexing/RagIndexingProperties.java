package org.signalengine.infrastructure.rag.indexing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the RAG indexing pipeline (docs/adr/0012-pgvector-persistence-and-indexing.md).
 *
 * @param chunker which chunker the pipeline uses &mdash; {@code "structure-aware"} (the
 *     dependency-free deterministic default) or {@code "semantic"} (needs a configured embedding /
 *     boundary provider). The choice is recorded on every {@code IndexingReport} regardless.
 */
@ConfigurationProperties("signal-engine.rag.indexing")
public record RagIndexingProperties(@DefaultValue("structure-aware") String chunker) {}
