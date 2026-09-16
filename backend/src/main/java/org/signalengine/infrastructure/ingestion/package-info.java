/**
 * Ingestion infrastructure — outbound adapters for the ports in {@code
 * org.signalengine.application.ingestion} (docs/03-technical-spec.md Section 10.2;
 * docs/08-ingestion.md Section 4).
 *
 * <ul>
 *   <li>{@code HttpSourceCollector} — the one concrete collector: an HTTP(S) fetch of a source's
 *       reference URL. It does no format-specific parsing (no HTML body extraction, no feed
 *       parsing) — that is per-source-type work deferred with the source-type catalogue (Q1).
 *   <li>{@code OutboundUrlValidator} — the SSRF / URL-safety boundary applied before any fetch and
 *       to every redirect target (docs/03-technical-spec.md Section 20.2; docs/10-security.md
 *       Section 5–6). Returns the validated resolved addresses so the collector can pin them.
 *   <li>{@code OutboundAddressPinRegistry} / {@code OutboundAddressPinHolder} / {@code
 *       PinAwareResolver} / {@code PinningInetAddressResolverProvider} — the JEP 418 resolver
 *       provider that answers an in-flight fetch's hostname from its validated pin, closing the
 *       DNS-rebinding gap without touching the request URI or TLS
 *       (docs/adr/0017-outbound-fetch-ssrf-dns-rebinding.md).
 *   <li>{@code ConfigurableSourceCollectorRegistry} — maps a source type to its collector from
 *       configuration.
 *   <li>{@code IngestionProperties} — fetch limits (schemes, size, redirects, timeouts), all
 *       provisional defaults open to tuning (T9).
 * </ul>
 */
package org.signalengine.infrastructure.ingestion;
