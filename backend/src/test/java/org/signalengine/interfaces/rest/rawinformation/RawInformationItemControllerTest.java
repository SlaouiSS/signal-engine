package org.signalengine.interfaces.rest.rawinformation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.usecase.ReviewRawInformationUseCase;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RawInformationItemController.class)
@ActiveProfiles("test")
class RawInformationItemControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ReviewRawInformationUseCase reviewRawInformation;

  @Test
  void returnsARawInformationItemWithItsProcessingState() throws Exception {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    when(reviewRawInformation.findRawInformationItem(id))
        .thenReturn(
            Optional.of(
                new RawInformationItem(
                    id,
                    UUID.randomUUID(),
                    "ext-1",
                    "hash",
                    "https://example.test/a",
                    "raw",
                    null,
                    "en",
                    null,
                    now,
                    new ProcessingState("received", null, null, null, now),
                    null,
                    now,
                    now)));

    mockMvc
        .perform(get("/api/v1/raw-information-items/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contentHash").value("hash"))
        .andExpect(jsonPath("$.language").value("en"))
        .andExpect(jsonPath("$.processingState.state").value("received"));
  }

  @Test
  void unknownItemReturns404Problem() throws Exception {
    UUID unknownId = UUID.randomUUID();
    when(reviewRawInformation.findRawInformationItem(unknownId)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/raw-information-items/{id}", unknownId))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }
}
