package org.signalengine.infrastructure.ingestion;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ingestion fetch limits and collector configuration.
 *
 * <p>Every value here is a <strong>provisional default open to tuning</strong>
 * (docs/03-technical-spec.md Section 13.4, T9; docs/10-security.md Section 5 — "no exact numeric
 * limit is chosen here"). {@code httpCollectorSourceTypes} is a provisional binding of the
 * HTTP-fetch collector to a source type; it is not a resolution of the source-type catalogue (Q1).
 */
@ConfigurationProperties("signal-engine.ingestion")
public record IngestionProperties(
    @DefaultValue("https") Set<String> allowedSchemes,
    @DefaultValue("5242880") long maxResponseBytes,
    @DefaultValue("3") int maxRedirects,
    @DefaultValue("10s") Duration connectTimeout,
    @DefaultValue("30s") Duration requestTimeout,
    @DefaultValue("http") Set<String> httpCollectorSourceTypes) {}
