/**
 * The application's outbound boundary to the Python AI capability service
 * (docs/03-technical-spec.md Section 8-9; docs/06-ai-agents.md Section 3, 9;
 * docs/adr/0006-ai-java-python-foundation.md).
 *
 * <p>A use case that needs an AI judgement depends only on {@link
 * org.signalengine.application.ai.AiCapabilityInvoker} — a versioned, capability-addressed,
 * provider-independent call that returns a validated typed result or a typed {@link
 * org.signalengine.application.ai.AiError}. It knows nothing of HTTP, JSON, the Python service, or
 * any LLM provider; those live in {@code org.signalengine.infrastructure.ai}.
 *
 * <p>Task 6A ships only this port and its HTTP adapter, proven end to end by a trivial {@code echo}
 * capability. The first real capability port (near-duplicate assessment) arrives in Task 6B.
 */
package org.signalengine.application.ai;
