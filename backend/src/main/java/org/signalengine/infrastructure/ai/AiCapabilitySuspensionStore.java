package org.signalengine.infrastructure.ai;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PostgreSQL persistence of {@link AiCapabilitySuspension}s (migration V12). Plain {@link
 * JdbcClient}, like the other single-purpose infrastructure stores: the upsert computes the
 * exponential cooldown in SQL so that concurrent refusals cannot lose an increment, and the probe
 * claim is one conditional {@code UPDATE} so that exactly one caller wins it. It also keeps the
 * last success of each capability, the evidence that tells a refused configuration from a request
 * that is rejected for its own sake (docs/adr/0018-pipeline-reliability.md).
 */
class AiCapabilitySuspensionStore {

  private final JdbcClient jdbcClient;

  AiCapabilitySuspensionStore(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  Optional<AiCapabilitySuspension> find(String capability) {
    return jdbcClient
        .sql(
            """
            SELECT capability, suspended_since, suspended_until, consecutive_failures,
                   provider_status, reason
            FROM ai_capability_suspension
            WHERE capability = :capability
            """)
        .param("capability", capability)
        .query((rs, rowNumber) -> toSuspension(rs))
        .optional();
  }

  /**
   * Suspends the capability, or lengthens an existing suspension: the cooldown is {@code
   * initialCooldown} the first time and doubles with each further refusal, up to {@code
   * maxCooldown}.
   */
  AiCapabilitySuspension suspend(
      String capability,
      Instant now,
      Duration initialCooldown,
      Duration maxCooldown,
      Integer providerStatus,
      String reason) {
    return jdbcClient
        .sql(
            """
            INSERT INTO ai_capability_suspension (
                capability, suspended_since, suspended_until, consecutive_failures,
                provider_status, reason, updated_at)
            VALUES (
                :capability, :now,
                CAST(:now AS timestamptz)
                    + make_interval(secs => CAST(:initialSeconds AS double precision)),
                1, :providerStatus, :reason, :now)
            ON CONFLICT (capability) DO UPDATE SET
                suspended_until = CAST(:now AS timestamptz) + make_interval(
                    secs => LEAST(
                        CAST(:maxSeconds AS double precision),
                        CAST(:initialSeconds AS double precision)
                            * power(2, ai_capability_suspension.consecutive_failures))),
                consecutive_failures = ai_capability_suspension.consecutive_failures + 1,
                provider_status = EXCLUDED.provider_status,
                reason = EXCLUDED.reason,
                updated_at = :now
            RETURNING capability, suspended_since, suspended_until, consecutive_failures,
                      provider_status, reason
            """)
        .param("capability", capability)
        .param("now", utc(now))
        .param("initialSeconds", (double) initialCooldown.toSeconds())
        .param("maxSeconds", (double) maxCooldown.toSeconds())
        .param("providerStatus", providerStatus)
        .param("reason", reason)
        .query((rs, rowNumber) -> toSuspension(rs))
        .single();
  }

  /**
   * Claims the single right to probe a suspension whose cooldown has ended, by pushing {@code
   * suspended_until} forward to {@code probeLeaseEnd}. Returns {@code true} for exactly one of any
   * number of concurrent callers.
   */
  boolean claimProbe(String capability, Instant now, Instant probeLeaseEnd) {
    return jdbcClient
            .sql(
                """
                UPDATE ai_capability_suspension
                SET suspended_until = :probeLeaseEnd, updated_at = :now
                WHERE capability = :capability AND suspended_until <= :now
                """)
            .param("capability", capability)
            .param("now", utc(now))
            .param("probeLeaseEnd", utc(probeLeaseEnd))
            .update()
        == 1;
  }

  /**
   * The probe failed for a reason that does not refuse the configuration: let the next caller try.
   */
  void releaseProbe(String capability, Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE ai_capability_suspension
            SET suspended_until = :now, updated_at = :now
            WHERE capability = :capability
            """)
        .param("capability", capability)
        .param("now", utc(now))
        .update();
  }

  /** Records that a call of the capability succeeded: evidence that its configuration works. */
  void recordSuccess(String capability, Instant now) {
    jdbcClient
        .sql(
            """
            INSERT INTO ai_capability_health (capability, last_success_at)
            VALUES (:capability, :now)
            ON CONFLICT (capability) DO UPDATE SET
                last_success_at = :now,
                consecutive_transient_failures = 0,
                consecutive_rejections = 0
            """)
        .param("capability", capability)
        .param("now", utc(now))
        .update();
  }

  /** Counts one more request refused as invalid since the last success, and returns the count. */
  int recordRejection(String capability) {
    return jdbcClient
        .sql(
            """
            INSERT INTO ai_capability_health (capability, consecutive_rejections)
            VALUES (:capability, 1)
            ON CONFLICT (capability) DO UPDATE SET
                consecutive_rejections = ai_capability_health.consecutive_rejections + 1
            RETURNING consecutive_rejections
            """)
        .param("capability", capability)
        .query(Integer.class)
        .single();
  }

  /** Counts one more transient failure since the last success, and returns the count. */
  int recordTransientFailure(String capability) {
    return jdbcClient
        .sql(
            """
            INSERT INTO ai_capability_health (capability, consecutive_transient_failures)
            VALUES (:capability, 1)
            ON CONFLICT (capability) DO UPDATE SET
                consecutive_transient_failures =
                    ai_capability_health.consecutive_transient_failures + 1
            RETURNING consecutive_transient_failures
            """)
        .param("capability", capability)
        .query(Integer.class)
        .single();
  }

  /** When a call of the capability last succeeded, if it ever did. */
  Optional<Instant> lastSuccess(String capability) {
    return jdbcClient
        .sql(
            """
            SELECT last_success_at FROM ai_capability_health
            WHERE capability = :capability AND last_success_at IS NOT NULL
            """)
        .param("capability", capability)
        .query((rs, rowNumber) -> rs.getObject("last_success_at", OffsetDateTime.class).toInstant())
        .optional();
  }

  /** A call succeeded: the capability is no longer suspended. */
  void clear(String capability) {
    jdbcClient
        .sql("DELETE FROM ai_capability_suspension WHERE capability = :capability")
        .param("capability", capability)
        .update();
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static AiCapabilitySuspension toSuspension(ResultSet rs) throws SQLException {
    int providerStatus = rs.getInt("provider_status");
    boolean providerStatusKnown = !rs.wasNull();
    return new AiCapabilitySuspension(
        rs.getString("capability"),
        rs.getObject("suspended_since", OffsetDateTime.class).toInstant(),
        rs.getObject("suspended_until", OffsetDateTime.class).toInstant(),
        rs.getInt("consecutive_failures"),
        providerStatusKnown ? providerStatus : null,
        rs.getString("reason"));
  }
}
