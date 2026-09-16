/**
 * Ingestion orchestration and its ports (docs/08-ingestion.md; docs/03-technical-spec.md Section
 * 10; docs/11-roadmap.md Phase 2 Task 5).
 *
 * <p>Java owns the pipeline: collect from a configured source, normalise the collected content
 * deterministically, reject exact duplicates, and persist a {@link org.signalengine.domain
 * .RawInformationItem} with its provenance and processing state. No AI/LLM calls, no semantic
 * near-duplicate detection — those are later tasks.
 *
 * <p>Ports declared here:
 *
 * <ul>
 *   <li>{@code CollectFromSourceUseCase} — the input port a scheduler (or a future manual trigger)
 *       calls;
 *   <li>{@code SourceCollector} / {@code SourceCollectorRegistry} — output ports; one adapter per
 *       source type lives in {@code org.signalengine.infrastructure.ingestion};
 *   <li>{@code ContentNormalizer} — the deterministic normalisation step, replaceable.
 * </ul>
 *
 * <p>Nothing here depends on Spring, HTTP client types, JDBC, or SQL.
 */
package org.signalengine.application.ingestion;
