package org.signalengine.interfaces.rest.cors;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cross-origin browser access to the exposed {@code /api/v1} business API
 * (docs/03-technical-spec.md Section 15.2 — "API/frontend: bind address, CORS for local dev, base
 * path").
 *
 * <p>The frontend and backend are separate origins even in local development — different ports on
 * {@code localhost}/{@code 127.0.0.1} (the frontend's own default, {@code frontend/vite.config.ts}
 * and {@code docker-compose.yml}) already count as cross-origin to a browser. Without an explicit
 * allow-list here, the browser silently blocks every {@code /api/v1} request the frontend makes and
 * the frontend cannot reach the backend at all, regardless of whether the URL itself is reachable.
 *
 * <p>{@code allowedOrigins} defaults to the frontend dev server's own default origins. This is a
 * local-development default, not a production-readiness decision — a future non-local deployment
 * must set {@code API_CORS_ALLOWED_ORIGINS} explicitly for its real frontend origin.
 */
@ConfigurationProperties("signal-engine.api")
public record ApiCorsProperties(
    @DefaultValue({"http://localhost:5173", "http://127.0.0.1:5173"})
        List<String> corsAllowedOrigins) {

  public ApiCorsProperties {
    if (corsAllowedOrigins.isEmpty()) {
      throw new IllegalArgumentException(
          "signal-engine.api.cors-allowed-origins must not be empty");
    }
  }
}
