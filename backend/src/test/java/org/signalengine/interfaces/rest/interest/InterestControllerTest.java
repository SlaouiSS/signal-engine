package org.signalengine.interfaces.rest.interest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.usecase.ManageInterestsUseCase;
import org.signalengine.domain.Interest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InterestController.class)
@ActiveProfiles("test")
class InterestControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ManageInterestsUseCase manageInterests;

  @Test
  void addsAnInterestAndReturns201() throws Exception {
    Interest added =
        new Interest(
            UUID.randomUUID(), "AI_AND_TECHNOLOGY", "LLMs", true, Instant.now(), Instant.now());
    when(manageInterests.addInterest("AI_AND_TECHNOLOGY", "LLMs")).thenReturn(added);

    mockMvc
        .perform(
            post("/api/v1/interests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"areaOfInterestCode":"AI_AND_TECHNOLOGY","description":"LLMs"}"""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.areaOfInterestCode").value("AI_AND_TECHNOLOGY"))
        .andExpect(jsonPath("$.enabled").value(true));
  }

  @Test
  void mapsAnUnknownAreaToA400InvalidInputProblem() throws Exception {
    when(manageInterests.addInterest(eq("NOPE"), any()))
        .thenThrow(new InvalidInputException("unknown area of interest: NOPE"));

    mockMvc
        .perform(
            post("/api/v1/interests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"areaOfInterestCode":"NOPE","description":"x"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.detail").value("unknown area of interest: NOPE"));
  }

  @Test
  void rejectsAMissingDescriptionWith400Validation() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/interests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"areaOfInterestCode":"AI_AND_TECHNOLOGY"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    verify(manageInterests, never()).addInterest(any(), any());
  }

  @Test
  void updatingAnUnknownInterestReturns404() throws Exception {
    UUID unknownId = UUID.randomUUID();
    when(manageInterests.updateInterestDescription(eq(unknownId), any()))
        .thenReturn(Optional.empty());

    mockMvc
        .perform(
            put("/api/v1/interests/{id}", unknownId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"description":"new"}"""))
        .andExpect(status().isNotFound());
  }

  @Test
  void enablesAnInterest() throws Exception {
    UUID id = UUID.randomUUID();
    when(manageInterests.enableInterest(id))
        .thenReturn(
            Optional.of(
                new Interest(
                    id, "LAW_AND_REGULATION", "AI Act", true, Instant.now(), Instant.now())));

    mockMvc
        .perform(post("/api/v1/interests/{id}/enable", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true));
  }

  @Test
  void listsInterests() throws Exception {
    when(manageInterests.listInterests())
        .thenReturn(
            java.util.List.of(
                new Interest(
                    UUID.randomUUID(),
                    "FASHION_AND_CLOTHING",
                    "sustainable textiles",
                    true,
                    Instant.now(),
                    Instant.now())));

    mockMvc
        .perform(get("/api/v1/interests"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].description").value("sustainable textiles"));
  }
}
