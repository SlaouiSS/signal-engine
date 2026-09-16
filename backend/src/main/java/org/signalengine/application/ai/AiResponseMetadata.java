package org.signalengine.application.ai;

/**
 * Model/provider provenance for a successful capability call (docs/03-technical-spec.md Section
 * 8.2). Recorded alongside the result so a later behaviour change (a model swap, a prompt bump) is
 * traceable.
 */
public record AiResponseMetadata(
    String provider, String model, String promptVersion, long durationMillis) {}
