package org.signalengine.interfaces.rest.cors;

import org.signalengine.interfaces.rest.ApiV1;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the configured frontend origin(s) to call the exposed {@code /api/v1} business API from a
 * browser (composition root — CLAUDE.md Section 9). Scoped to {@link ApiV1#BASE_PATH} only: Swagger
 * UI, {@code /v3/api-docs}, and the actuator endpoints are not called from the browser SPA and keep
 * the servlet container's default (same-origin) behavior.
 *
 * <p>Only the HTTP methods the {@code /api/v1} API actually exposes are allowed (docs/03-technical-
 * spec.md Section 6.6) — no method is enabled speculatively.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiCorsProperties.class)
class ApiCorsConfiguration implements WebMvcConfigurer {

  private final ApiCorsProperties properties;

  ApiCorsConfiguration(ApiCorsProperties properties) {
    this.properties = properties;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping(ApiV1.BASE_PATH + "/**")
        .allowedOrigins(properties.corsAllowedOrigins().toArray(String[]::new))
        .allowedMethods("GET", "POST", "PUT")
        .allowedHeaders("Content-Type")
        .maxAge(3600);
  }
}
