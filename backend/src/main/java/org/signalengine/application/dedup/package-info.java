/**
 * Semantic near-duplicate grouping: turn a normalised {@link
 * org.signalengine.domain.RawInformationItem} into an association with a {@link
 * org.signalengine.domain.RelevantInformation} record — either an existing one it corroborates as a
 * near-duplicate, or a new one it anchors (docs/05-data-model.md Section 9, 18;
 * docs/08-ingestion.md Section 9; docs/11-roadmap.md Phase 5;
 * docs/adr/0007-semantic-deduplication-relevant-information.md).
 *
 * <p>Pipeline: {@code normalized item -> bounded candidate selection -> AI near-duplicate
 * assessment -> Java decision -> associate | create}. The AI assessment is advisory; Java owns the
 * candidate set, the grouping decision, the processing-state transition, and persistence. Java
 * calls the AI only through {@link org.signalengine.application.ai.AiCapabilityInvoker} (Task 6A).
 * Nothing here depends on Spring, HTTP, JDBC, or SQL.
 *
 * <p>This task does not create Signals, assess importance or relevance, summarise, or alert.
 */
package org.signalengine.application.dedup;
