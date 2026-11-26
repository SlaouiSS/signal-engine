package org.signalengine.infrastructure.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How a capability whose provider configuration was refused is suspended and probed again
 * (docs/adr/0018-pipeline-reliability.md). All values are provisional defaults open to tuning, like
 * the other retry and timeout values (docs/03-technical-spec.md Section 13.3, 24, T9).
 *
 * @param initialCooldown how long the capability stays suspended after the first refusal
 * @param maxCooldown the cap of the exponential cooldown (doubled after each refused probe)
 * @param probeLease how long the one caller allowed to probe holds the right to do so; it must
 *     exceed the call timeout, and only matters when a probing caller dies before reporting
 * @param evidenceWindow how recent a successful call must be to prove the capability works, so a
 *     permanent rejection of one request is taken as that request's own fault and not as a refused
 *     configuration
 */
@ConfigurationProperties("signal-engine.ai.suspension")
public record AiCapabilitySuspensionProperties(
    @DefaultValue("15m") Duration initialCooldown,
    @DefaultValue("6h") Duration maxCooldown,
    @DefaultValue("6m") Duration probeLease,
    @DefaultValue("24h") Duration evidenceWindow) {}
