package org.signalengine.interfaces.rest.activity;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.signalengine.application.usecase.ReviewActivityUseCase;
import org.signalengine.domain.ActivityRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ActivityController.class)
@ActiveProfiles("test")
class ActivityControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ReviewActivityUseCase reviewActivity;

  @Test
  void listsRecentActivityUsingTheDefaultLimit() throws Exception {
    when(reviewActivity.listRecentActivity(50))
        .thenReturn(List.of(activityRecord(), activityRecord()));

    mockMvc
        .perform(get("/api/v1/activity"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].category").value("collection"));

    verify(reviewActivity).listRecentActivity(50);
  }

  @Test
  void passesAnExplicitLimitThrough() throws Exception {
    when(reviewActivity.listRecentActivity(5)).thenReturn(List.of());

    mockMvc.perform(get("/api/v1/activity").param("limit", "5")).andExpect(status().isOk());

    verify(reviewActivity).listRecentActivity(5);
  }

  @Test
  void rejectsALimitAboveTheMaximumWith400() throws Exception {
    mockMvc
        .perform(get("/api/v1/activity").param("limit", "5000"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

    verify(reviewActivity, never()).listRecentActivity(ArgumentMatchers.anyInt());
  }

  @Test
  void unknownActivityRecordReturns404() throws Exception {
    UUID unknownId = UUID.randomUUID();
    when(reviewActivity.findActivityRecord(unknownId)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/activity/{id}", unknownId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  private static ActivityRecord activityRecord() {
    return new ActivityRecord(
        UUID.randomUUID(), Instant.now(), "collection", "success", "collected 3 items", null, null);
  }
}
