package org.signalengine.infrastructure.ai;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the backend reaches the Python AI capability service (docs/03-technical-spec.md Section 8;
 * docs/adr/0006-ai-java-python-foundation.md).
 *
 * <p>{@code requestTimeout} is the Java client's per-call ceiling; it is set longer than the Python
 * service's own model timeout so the side that best understands a slow call reports it first
 * (docs/03-technical-spec.md Section 8.5). All values are environment-overridable and carry
 * non-secret local defaults.
 */
@ConfigurationProperties("signal-engine.ai")
public record AiFoundationProperties(
    @DefaultValue("http://127.0.0.1:8100") URI agentsBaseUrl,
    @DefaultValue("90s") Duration requestTimeout,
    @DefaultValue("5s") Duration connectTimeout) {}
