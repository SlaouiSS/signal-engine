/**
 * Wiring for the relevance/importance/Signal/Summary pipeline ({@code
 * org.signalengine.application.signal}). No adapter of its own — the three AI steps reuse {@code
 * org.signalengine.infrastructure.ai}; this package holds only the composition root and its
 * {@code @ConfigurationProperties} (docs/adr/0008-relevance-importance-signal-summary.md).
 */
package org.signalengine.infrastructure.signal;
