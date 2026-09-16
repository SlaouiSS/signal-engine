/**
 * Infrastructure layer — outbound adapters.
 *
 * <p>Spring Data JDBC repository implementations, Flyway-managed schema, the Python agent HTTP
 * client, the Spring-AI-backed LLM provider adapter (isolated), source-connector implementations,
 * and telemetry (docs/04-architecture.md Section 4.2). Spring and third-party SDKs live here and
 * nowhere further inward.
 *
 * <p>Phase 1 contains only the composition-root placeholder (docs/11-roadmap.md Section 4).
 */
package org.signalengine.infrastructure;
