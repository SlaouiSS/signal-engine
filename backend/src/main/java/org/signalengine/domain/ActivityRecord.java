package org.signalengine.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Basic, bounded, product-level visibility into what the system did (docs/05-data-model.md Section
 * 13). Append-only. {@code outcome}, {@code message}, {@code sourceId}, and {@code
 * rawInformationItemId} may be {@code null} — some activity is a system operation tied to neither a
 * source nor an item.
 */
public record ActivityRecord(
    UUID id,
    Instant occurredAt,
    String category,
    String outcome,
    String message,
    UUID sourceId,
    UUID rawInformationItemId) {}
