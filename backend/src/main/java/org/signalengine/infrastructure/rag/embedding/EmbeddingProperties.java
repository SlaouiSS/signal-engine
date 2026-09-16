package org.signalengine.infrastructure.rag.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the RAG embedding path (docs/adr/0011-embedding-contract-and-local-model.md).
 *
 * <p>The <b>concrete model</b> is not configured here &mdash; it is the Python service's {@code
 * OLLAMA_EMBEDDING_MODEL}. Only the transport binding and an optional dimension guard live on the
 * Java side. {@code expectedDimension} stays {@code 0} until the embedding model (T3) is fixed;
 * once it is, setting it makes a mismatched Python configuration fail fast rather than silently
 * producing unusable vectors.
 *
 * @param capability the Python capability name
 * @param contractVersion the capability contract version
 * @param expectedDimension the required vector dimension, or {@code 0} to trust the service
 */
@ConfigurationProperties("signal-engine.rag.embedding")
public record EmbeddingProperties(
    @DefaultValue("embed") String capability,
    @DefaultValue("1") int contractVersion,
    @DefaultValue("0") int expectedDimension) {}
