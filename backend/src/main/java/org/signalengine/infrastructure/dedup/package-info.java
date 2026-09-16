/**
 * Wiring for semantic near-duplicate grouping ({@code org.signalengine.application.dedup}). No
 * adapter of its own — the AI call reuses {@code org.signalengine.infrastructure.ai}; this package
 * only holds the composition root and its {@code @ConfigurationProperties}
 * (docs/adr/0007-semantic-deduplication-relevant-information.md).
 */
package org.signalengine.infrastructure.dedup;
