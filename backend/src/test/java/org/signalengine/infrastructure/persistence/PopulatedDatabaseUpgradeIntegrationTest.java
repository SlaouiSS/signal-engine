package org.signalengine.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Migration V14 applied to a database that already holds data from V13, in a schema of its own:
 * existing rows are kept and start with clean retry counts, so pending work stays pending.
 */
class PopulatedDatabaseUpgradeIntegrationTest extends AbstractPersistenceIntegrationTest {

  @Autowired private DataSource dataSource;

  @Test
  void existingRowsSurviveV14WithNoFailuresCountedAndNoWaitingTime() throws SQLException {
    String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    migrate(schema, "13");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + schema + ", public");
      statement.execute(
          "INSERT INTO source (id, type, name, reference) VALUES"
              + " ('11111111-1111-1111-1111-111111111111', 'http', 's', 'https://ex.test')");
      statement.execute(
          "INSERT INTO raw_information_item (source_id, content_hash, processing_state) VALUES"
              + " ('11111111-1111-1111-1111-111111111111', 'h', 'deduplicated')");
      statement.execute(
          "INSERT INTO rag_content_index (content_id, revision, failed_attempts, created_at,"
              + " updated_at) VALUES ('c', 3, 5, now(), now())");
      statement.execute(
          "INSERT INTO ai_capability_health (capability, last_success_at)"
              + " VALUES ('relevance', now())");

      migrate(schema, "14");

      assertThat(
              single(
                  statement,
                  "SELECT count(*) FROM raw_information_item WHERE transient_failures = 0"
                      + " AND unexpected_failures = 0 AND next_attempt_at IS NULL"))
          .isEqualTo(1);
      assertThat(
              single(
                  statement,
                  "SELECT count(*) FROM rag_content_index WHERE failed_attempts = 5"
                      + " AND blocked_since IS NULL AND attempted_fingerprint IS NULL"))
          .as("eligible again once: what its old attempts were made against is unknown")
          .isEqualTo(1);
      assertThat(
              single(
                  statement,
                  "SELECT count(*) FROM ai_capability_health"
                      + " WHERE consecutive_transient_failures = 0"))
          .isEqualTo(1);
      statement.execute("SET search_path TO public");
      statement.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private void migrate(String schema, String target) {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(schema)
        .initSql("SET search_path TO " + schema + ", public")
        .target(target)
        .load()
        .migrate();
  }

  private static long single(Statement statement, String query) throws SQLException {
    try (ResultSet result = statement.executeQuery(query)) {
      result.next();
      return result.getLong(1);
    }
  }
}
