package org.signalengine.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * No database operation of the application can block for ever (docs/03-technical-spec.md Section
 * 13.4): statements, lock waits and whole transactions are limited on every pooled connection. The
 * limits are shortened here so each can be reached.
 */
@TestPropertySource(
    properties =
        "spring.datasource.hikari.connection-init-sql="
            + "SET statement_timeout = '1s'; SET lock_timeout = '300ms';"
            + " SET transaction_timeout = '2s'")
class DatabaseTimeoutIntegrationTest extends AbstractPersistenceIntegrationTest {

  @Autowired private JdbcClient jdbcClient;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void aStatementRunningTooLongIsCancelled() {
    assertThatThrownBy(() -> sleep("3")).rootCause().hasMessageContaining("statement timeout");
  }

  @Test
  void aWaitForALockHeldByAnotherConnectionIsAbandoned() throws SQLException {
    jdbcClient.sql("CREATE TABLE IF NOT EXISTS lock_timeout_probe (id int)").update();
    try (Connection holder = dataSource.getConnection();
        Statement statement = holder.createStatement()) {
      holder.setAutoCommit(false);
      statement.execute("LOCK TABLE lock_timeout_probe IN ACCESS EXCLUSIVE MODE");

      assertThatThrownBy(
              () -> jdbcClient.sql("SELECT count(*) FROM lock_timeout_probe").query().singleRow())
          .rootCause()
          .hasMessageContaining("lock timeout");
      holder.rollback();
    }
  }

  @Test
  void aTransactionOfManyShortStatementsIsStillBounded() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      for (int statement = 0; statement < 6; statement++) {
                        sleep("0.5");
                      }
                    }))
        .isInstanceOf(RuntimeException.class);

    assertThat(jdbcClient.sql("SELECT 1").query(Integer.class).single())
        .as("the pool replaces the terminated connection")
        .isEqualTo(1);
  }

  private void sleep(String seconds) {
    jdbcClient.sql("SELECT pg_sleep(" + seconds + ")").query().singleRow();
  }
}
