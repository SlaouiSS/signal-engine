package org.signalengine.infrastructure.ai;

import java.time.Instant;

/**
 * A persisted suspension of one AI capability (migration V12). {@code providerStatus} is {@code
 * null} when the provider's HTTP status is not known.
 */
record AiCapabilitySuspension(
    String capability,
    Instant suspendedSince,
    Instant suspendedUntil,
    int consecutiveFailures,
    Integer providerStatus,
    String reason) {}
