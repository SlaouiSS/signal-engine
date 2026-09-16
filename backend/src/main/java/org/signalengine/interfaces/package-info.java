/**
 * Interfaces layer — inbound adapters.
 *
 * <p>REST controllers, DTOs, request validation, and scheduling entry points. Calls the
 * application's input ports (use-case interfaces), mapping requests to application commands and
 * back; contains no business logic (docs/03-technical-spec.md Section 6.6; CLAUDE.md Section 9).
 * Spring web types are allowed here. A technical/bootstrap endpoint with no application use case
 * (e.g. {@code MetaController}) may skip the input port and return its value directly.
 *
 * <p>Phase 1 contains only API conventions and health/metadata endpoints (docs/11-roadmap.md
 * Section 4).
 */
package org.signalengine.interfaces;
