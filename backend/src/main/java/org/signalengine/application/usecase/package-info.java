/**
 * Application <strong>input ports</strong> — the use-case interfaces the interface layer (REST
 * controllers, later) depends on (docs/04-architecture.md Section 4.2; CLAUDE.md Section 9).
 *
 * <p>Each interface is one business capability; each {@code Default*} class is its implementation.
 * Implementations orchestrate domain types and the output ports declared in {@code
 * org.signalengine.application.persistence}. Nothing here depends on Spring, JDBC, SQL, or any
 * infrastructure type; the implementations are plain classes wired by the composition root ({@code
 * org.signalengine.infrastructure.config.UseCaseConfiguration}).
 *
 * <p>Scope for this slice (docs/11-roadmap.md Phase 2): only operations the domain model and the
 * repository ports already support. No business REST endpoints, ingestion, AI, RAG, alerts, or
 * authorization.
 */
package org.signalengine.application.usecase;
