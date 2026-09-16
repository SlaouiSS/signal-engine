package org.signalengine.interfaces.rest;

/**
 * Shared REST conventions for the versioned HTTP API.
 *
 * <p>All product endpoints live under {@link #BASE_PATH} and return JSON (docs/03-technical-spec.md
 * Section 6.6, 12.1). Errors use RFC 9457 {@code application/problem+json} (enabled via {@code
 * spring.mvc.problemdetails.enabled}). Actuator endpoints are deliberately <em>not</em> under this
 * path.
 */
public final class ApiV1 {

  /** Base path for every versioned product endpoint. */
  public static final String BASE_PATH = "/api/v1";

  private ApiV1() {}
}
