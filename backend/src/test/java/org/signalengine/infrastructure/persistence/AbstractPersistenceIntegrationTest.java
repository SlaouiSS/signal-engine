package org.signalengine.infrastructure.persistence;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for persistence integration tests: one real PostgreSQL 17 + pgvector database via
 * Testcontainers, shared across every integration test class in the JVM (singleton-container
 * pattern — docs/03-technical-spec.md Section 4.9, 16.1, 16.5). The real Flyway migrations are
 * applied on context startup. Tagged {@code integration} so these run only in the dedicated {@code
 * integrationTest} task.
 *
 * <p>The container is started once when this class loads and left running for the lifetime of the
 * JVM; Testcontainers' Ryuk reaps it on exit. {@code @ServiceConnection} wires Spring Boot's {@code
 * DataSource} to it.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Tag("integration")
public abstract class AbstractPersistenceIntegrationTest {

  @ServiceConnection protected static final PostgreSQLContainer POSTGRES = startedPostgres();

  private static PostgreSQLContainer startedPostgres() {
    PostgreSQLContainer container =
        new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    container.start();
    return container;
  }
}
