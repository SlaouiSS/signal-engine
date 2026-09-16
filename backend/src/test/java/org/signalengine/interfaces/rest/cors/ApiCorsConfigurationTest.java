package org.signalengine.interfaces.rest.cors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.signalengine.interfaces.rest.ApiV1;
import org.signalengine.interfaces.rest.MetaController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The browser-facing CORS allow-list for {@code /api/v1} ({@link ApiCorsProperties}). Without it,
 * every request the frontend dev server makes is silently blocked by the browser, surfacing to the
 * user as "Could not reach the backend" even though the backend is reachable and the URL is correct
 * (docs/03-technical-spec.md Section 15.2 — "CORS for local dev").
 *
 * <p>{@link MetaController} is reused as the sliced endpoint purely because it needs no mocked
 * use-case ports; the behavior under test is the CORS configuration, not {@code /meta} itself.
 */
@WebMvcTest(MetaController.class)
@ActiveProfiles("test")
class ApiCorsConfigurationTest {

  private static final String ALLOWED_ORIGIN = "http://localhost:5173";
  private static final String OTHER_ALLOWED_ORIGIN = "http://127.0.0.1:5173";
  private static final String DISALLOWED_ORIGIN = "http://evil.test";

  @Autowired private MockMvc mockMvc;

  @Test
  void allowsTheFrontendDevServerOriginOnAnActualRequest() throws Exception {
    mockMvc
        .perform(get(ApiV1.BASE_PATH + "/meta").header("Origin", ALLOWED_ORIGIN))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
  }

  @Test
  void allowsTheAlternateLoopbackOriginToo() throws Exception {
    mockMvc
        .perform(get(ApiV1.BASE_PATH + "/meta").header("Origin", OTHER_ALLOWED_ORIGIN))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", OTHER_ALLOWED_ORIGIN));
  }

  @Test
  void answersAPreflightRequestForAnAllowedOrigin() throws Exception {
    mockMvc
        .perform(
            options(ApiV1.BASE_PATH + "/meta")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
  }

  @Test
  void rejectsAPreflightRequestForAnOriginNotOnTheAllowList() throws Exception {
    mockMvc
        .perform(
            options(ApiV1.BASE_PATH + "/meta")
                .header("Origin", DISALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }
}
