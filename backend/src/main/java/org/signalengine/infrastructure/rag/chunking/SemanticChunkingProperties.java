package org.signalengine.infrastructure.rag.chunking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for RAG chunking (docs/adr/0010-indexing-foundation-and-semantic-chunking.md).
 *
 * <p><b>Every value here is provisional</b> &mdash; none is a tuned or benchmarked figure. They are
 * chosen only so a real implementation can run, and are environment-overridable. The chunking
 * parameters proper (passage size, overlap, unit granularity) remain open (docs/07-rag.md Section
 * 21).
 *
 * @param maxInputTokens the context-window size the semantic chunker plans windows against;
 *     deliberately conservative so window planning never overflows a smaller real model
 * @param defaultMediaType media type assumed for content that does not declare one
 * @param structureAwareMaxChars target passage size for the deterministic {@link
 *     org.signalengine.rag.chunking.StructureAwareChunker} baseline
 */
@ConfigurationProperties("signal-engine.rag.chunking")
public record SemanticChunkingProperties(
    @DefaultValue("8192") int maxInputTokens,
    @DefaultValue("text/markdown") String defaultMediaType,
    @DefaultValue("1200") int structureAwareMaxChars) {}
