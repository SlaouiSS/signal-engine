package org.signalengine.infrastructure.rag.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the RAG grounded-generation path (docs/adr/0015-rag-grounded-generator.md).
 *
 * <p>The concrete LLM, its provider, the prompt and the timeout are the Python service's
 * configuration ({@code AGENTS_LLM_PROVIDER}, {@code OLLAMA_MODEL}, {@code
 * AGENTS_LLM_REQUEST_TIMEOUT_SECONDS}, the {@code answer/v*} prompt asset) &mdash; nothing
 * model-specific lives on the Java side. Only the capability contract version is bindable here, so
 * it can be bumped without a code change.
 *
 * @param contractVersion the {@code answer} capability contract version to call
 */
@ConfigurationProperties("signal-engine.rag.generation")
public record RagGenerationProperties(@DefaultValue("1") int contractVersion) {}
