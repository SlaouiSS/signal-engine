/**
 * AI infrastructure — the outbound adapter for {@link org.signalengine.application.ai} ports
 * (docs/03-technical-spec.md Section 8-9; docs/adr/0006-ai-java-python-foundation.md).
 *
 * <ul>
 *   <li>{@code HttpAiCapabilityInvoker} — implements {@link
 *       org.signalengine.application.ai.AiCapabilityInvoker} with the JDK {@link
 *       java.net.http.HttpClient} (no Spring AI, no other dependency): builds the shared request
 *       envelope, calls {@code POST /capabilities/{name}/v{version}} on the Python service,
 *       validates the response envelope, and maps transport/timeout/contract failures to a typed
 *       {@link org.signalengine.application.ai.AiError}.
 *   <li>{@code SuspendingAiCapabilityInvoker} — decorates it so a capability whose provider
 *       configuration was refused is suspended (persisted, migration V12) and probed again under
 *       control, instead of being retried for every item (docs/adr/0018-pipeline-reliability.md).
 *   <li>{@code AiFoundationProperties} — the Python service URL and call timeouts, all
 *       environment-overridable.
 * </ul>
 *
 * <p>The concrete LLM provider (Ollama, initially) lives entirely on the Python side; nothing here
 * or in the application layer names it.
 */
package org.signalengine.infrastructure.ai;
