package org.signalengine.infrastructure.config;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.signalengine.application.pipeline.RetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounded retries of pipeline stages (docs/03-technical-spec.md Section 13.3). Provisional defaults
 * open to tuning (T9).
 *
 * @param maxAttempts attempts an item's stage gets against transient failures
 * @param maxUnexpectedFailures how often an unexpected error may repeat before work is set aside
 * @param baseDelay the wait after the first failure
 * @param factor how much the wait grows with each further failure
 * @param maxDelay the cap of the wait
 * @param jitterRatio the largest share of the wait that jitter may remove
 */
@ConfigurationProperties("signal-engine.pipeline.retry")
public record PipelineRetryProperties(
    @DefaultValue("3") int maxAttempts,
    @DefaultValue("5") int maxUnexpectedFailures,
    @DefaultValue("1h") Duration baseDelay,
    @DefaultValue("2.0") double factor,
    @DefaultValue("24h") Duration maxDelay,
    @DefaultValue("0.1") double jitterRatio) {

  /** The shared backoff with {@code transientAttempts} attempts against transient failures. */
  public RetryPolicy toRetryPolicy(int transientAttempts) {
    return new RetryPolicy(
        transientAttempts,
        maxUnexpectedFailures,
        baseDelay,
        factor,
        maxDelay,
        jitterRatio,
        () -> ThreadLocalRandom.current().nextDouble());
  }
}
