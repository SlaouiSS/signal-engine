package org.signalengine.infrastructure.dedup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds for semantic near-duplicate grouping
 * (docs/adr/0007-semantic-deduplication-relevant-information.md).
 *
 * <p>{@code candidatePoolSize} caps how many already-grouped items one incoming item is compared
 * against in a single AI call — this is what keeps assessment away from an O(n^2) all-history
 * comparison. {@code batchLimit} caps one sweep of the pending queue. Both are provisional defaults
 * open to tuning; neither resolves the near-duplicate threshold question (Q12/T15).
 */
@ConfigurationProperties("signal-engine.near-duplicate")
public record NearDuplicateGroupingProperties(
    @DefaultValue("10") int candidatePoolSize, @DefaultValue("50") int batchLimit) {}
