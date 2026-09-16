package org.signalengine.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata (docs/03-technical-spec.md Section 4.13).
 *
 * <p>springdoc generates the OpenAPI 3 document from the controllers; this only sets the top-level
 * information. The document is served at {@code /v3/api-docs} and the UI at {@code
 * /swagger-ui.html} in non-production profiles.
 */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI signalEngineOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Signal Engine API")
                .description(
                    "Versioned REST API for the Signal Engine backend: sources, areas of "
                        + "interest, interests, raw information, relevant information, signals, "
                        + "feedback, the activity feed, semantic search, and grounded question "
                        + "answering. Errors are RFC 9457 problem+json.")
                .version("v1"));
  }
}
