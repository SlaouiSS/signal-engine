/**
 * Application layer — use cases / orchestration services.
 *
 * <p>Declares the <strong>output ports</strong> (interfaces) it needs for everything external
 * (repositories, AI capabilities, source collectors, clock, id generation) and the <strong>input
 * ports</strong> (use-case interfaces) its inbound adapters call; owns transaction boundaries
 * (docs/04-architecture.md Section 4.2; CLAUDE.md Section 9). Depends only on the domain layer;
 * never talks directly to a database, an HTTP client, or an LLM.
 *
 * <p>Empty in Phase 1 — populated from Phase 3 onward (docs/11-roadmap.md).
 */
package org.signalengine.application;
