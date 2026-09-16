package org.signalengine;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the actuator health check used by Docker Compose is reachable and reports UP, including
 * the {@code db} component now that the backend has a real datasource. Runs against Testcontainers
 * PostgreSQL.
 */
@AutoConfigureMockMvc
class HealthEndpointTest extends AbstractPersistenceIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void actuatorHealthReportsUpIncludingTheDatabase() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.db.status").value("UP"));
  }
}
