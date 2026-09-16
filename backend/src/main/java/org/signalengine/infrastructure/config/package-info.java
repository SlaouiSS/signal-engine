/**
 * Composition root (docs/03-technical-spec.md Section 6.2; docs/04-architecture.md Section 4.2).
 *
 * <p>A single Spring configuration area that wires port implementations, resolving provider and
 * connector selection from configuration (docs/03-technical-spec.md Section 15) so the rest of the
 * code stays provider-agnostic.
 *
 * <p>Phase 1 wires only cross-cutting infrastructure (OpenAPI metadata). Port bindings arrive from
 * Phase 3 onward.
 */
package org.signalengine.infrastructure.config;
