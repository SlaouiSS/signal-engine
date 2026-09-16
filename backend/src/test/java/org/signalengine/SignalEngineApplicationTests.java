package org.signalengine;

import org.junit.jupiter.api.Test;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;

/**
 * Verifies the whole Spring application context starts. With a real Spring Data JDBC persistence
 * layer this needs a database, so it runs as an integration test against Testcontainers PostgreSQL.
 */
class SignalEngineApplicationTests extends AbstractPersistenceIntegrationTest {

  @Test
  void contextLoads() {}
}
