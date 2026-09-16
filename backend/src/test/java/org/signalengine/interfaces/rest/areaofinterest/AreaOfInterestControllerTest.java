package org.signalengine.interfaces.rest.areaofinterest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.signalengine.application.usecase.ReviewAreasOfInterestUseCase;
import org.signalengine.domain.AreaOfInterest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AreaOfInterestController.class)
@ActiveProfiles("test")
class AreaOfInterestControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ReviewAreasOfInterestUseCase reviewAreasOfInterest;

  @Test
  void listsAreasOfInterest() throws Exception {
    when(reviewAreasOfInterest.listAreasOfInterest())
        .thenReturn(List.of(new AreaOfInterest("AI_AND_TECHNOLOGY", "AI & Technology")));

    mockMvc
        .perform(get("/api/v1/areas-of-interest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("AI_AND_TECHNOLOGY"))
        .andExpect(jsonPath("$[0].name").value("AI & Technology"));
  }

  @Test
  void returns404ProblemForAnUnknownCode() throws Exception {
    when(reviewAreasOfInterest.findAreaOfInterest("NOPE")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/areas-of-interest/{code}", "NOPE"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }
}
