package org.signalengine.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The inbound request-body limit ({@code signal-engine.api.max-request-bytes}) enforced at the real
 * HTTP boundary of the exposed {@code /api/v1} API (docs/10-security.md Section 10). The limit is
 * pinned to a small value so exact byte-boundary behaviour is testable; a real client body is a few
 * hundred bytes.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "signal-engine.api.max-request-bytes=100")
class ApiRequestSizeLimitIntegrationTest extends AbstractPersistenceIntegrationTest {

  private static final int LIMIT = 100;
  private static final String PREFIX = "{\"type\":\"rss\",\"name\":\"";
  private static final String SUFFIX = "\",\"reference\":\"https://e.test/feed\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private SourceRepository sources;

  private static byte[] sourceJsonOfExactLength(int totalBytes) {
    int padding = totalBytes - PREFIX.length() - SUFFIX.length();
    String body = PREFIX + "n".repeat(padding) + SUFFIX;
    assertThat(body.getBytes(StandardCharsets.UTF_8)).hasSize(totalBytes);
    return body.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void aRequestBelowTheLimitIsHandledNormally() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sourceJsonOfExactLength(LIMIT - 20)))
        .andExpect(status().isCreated());
  }

  @Test
  void aRequestExactlyAtTheLimitIsHandledNormally() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sourceJsonOfExactLength(LIMIT)))
        .andExpect(status().isCreated());
  }

  @Test
  void aRequestOneByteOverTheLimitIsRejectedWith413ProblemJsonAndNeverPersisted() throws Exception {
    long before = sources.findAll().size();

    mockMvc
        .perform(
            post("/api/v1/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sourceJsonOfExactLength(LIMIT + 1)))
        .andExpect(status().is(413))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"))
        .andExpect(jsonPath("$.status").value(413))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());

    // the oversized request was rejected at the filter, before the controller / use case ran
    assertThat(sources.findAll()).hasSize((int) before);
  }

  @Test
  void aGrosslyOversizedRequestIsRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(("x".repeat(64 * 1024)).getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().is(413))
        .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
  }

  @Test
  void malformedJsonUnderTheLimitStillFailsAsABadRequestNotTooLarge() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"rss\",".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aBlankFieldUnderTheLimitStillFailsBeanValidation() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"\",\"name\":\"n\",\"reference\":\"https://e.test/f\"}"
                        .getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }
}
