package org.signalengine.application.signal;

import java.time.Instant;
import java.util.UUID;

/**
 * A raw information item whose knowledge base indexing is no longer attempted. The item and its
 * content are kept; this is the diagnostic (docs/adr/0019-pipeline-automation.md).
 *
 * @param rawInformationItemId the item that is not indexed
 * @param blockedSince when it was set aside
 * @param recoverable whether it may succeed if attempted again, so that it can be requeued
 * @param reason the last failure
 */
public record BlockedIndexing(
    UUID rawInformationItemId, Instant blockedSince, boolean recoverable, String reason) {}
