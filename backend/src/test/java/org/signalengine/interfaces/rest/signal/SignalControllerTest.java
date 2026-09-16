package org.signalengine.interfaces.rest.signal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.usecase.ReviewSignalsUseCase;
import org.signalengine.application.usecase.SubmitFeedbackUseCase;
import org.signalengine.domain.Feedback;
import org.signalengine.domain.FeedbackVerdict;
import org.signalengine.domain.Signal;
import org.signalengine.domain.SignalState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SignalController.class)
@ActiveProfiles("test")
class SignalControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ReviewSignalsUseCase reviewSignals;
  @MockitoBean private SubmitFeedbackUseCase submitFeedback;

  @Test
  void listsRecentSignalsUsingTheDefaultLimit() throws Exception {
    Signal signal =
        new Signal(
            UUID.randomUUID(), UUID.randomUUID(), SignalState.NEW, Instant.now(), Instant.now());
    when(reviewSignals.listSignals(50)).thenReturn(List.of(signal));

    mockMvc
        .perform(get("/api/v1/signals"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].state").value("NEW"));

    verify(reviewSignals).listSignals(50);
  }

  @Test
  void passesAnExplicitLimitThrough() throws Exception {
    when(reviewSignals.listSignals(5)).thenReturn(List.of());

    mockMvc.perform(get("/api/v1/signals").param("limit", "5")).andExpect(status().isOk());

    verify(reviewSignals).listSignals(5);
  }

  @Test
  void rejectsALimitAboveTheMaximumWith400() throws Exception {
    mockMvc
        .perform(get("/api/v1/signals").param("limit", "5000"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

    verify(reviewSignals, never()).listSignals(anyInt());
  }

  @Test
  void opensASignal() throws Exception {
    UUID id = UUID.randomUUID();
    UUID relevantInformationId = UUID.randomUUID();
    when(reviewSignals.findSignal(id))
        .thenReturn(
            Optional.of(
                new Signal(
                    id, relevantInformationId, SignalState.NEW, Instant.now(), Instant.now())));

    mockMvc
        .perform(get("/api/v1/signals/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("NEW"))
        .andExpect(jsonPath("$.relevantInformationId").value(relevantInformationId.toString()));
  }

  @Test
  void unknownSignalReturns404Problem() throws Exception {
    UUID unknownId = UUID.randomUUID();
    when(reviewSignals.findSignal(unknownId)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/signals/{id}", unknownId))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void submitsFeedbackAndReturns201() throws Exception {
    UUID signalId = UUID.randomUUID();
    when(submitFeedback.submitFeedback(signalId, FeedbackVerdict.RELEVANT))
        .thenReturn(
            Optional.of(
                new Feedback(
                    UUID.randomUUID(), signalId, FeedbackVerdict.RELEVANT, Instant.now())));

    mockMvc
        .perform(
            post("/api/v1/signals/{id}/feedback", signalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"verdict":"RELEVANT"}"""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.signalId").value(signalId.toString()))
        .andExpect(jsonPath("$.verdict").value("RELEVANT"));

    verify(submitFeedback).submitFeedback(signalId, FeedbackVerdict.RELEVANT);
  }

  @Test
  void feedbackForAnUnknownSignalReturns404() throws Exception {
    UUID unknownSignalId = UUID.randomUUID();
    when(submitFeedback.submitFeedback(eq(unknownSignalId), eq(FeedbackVerdict.NOT_RELEVANT)))
        .thenReturn(Optional.empty());

    mockMvc
        .perform(
            post("/api/v1/signals/{id}/feedback", unknownSignalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"verdict":"NOT_RELEVANT"}"""))
        .andExpect(status().isNotFound());
  }

  @Test
  void rejectsAMissingVerdictWith400() throws Exception {
    UUID signalId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/signals/{id}/feedback", signalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    verify(submitFeedback, never()).submitFeedback(any(), any());
  }

  @Test
  void rejectsAnUnknownVerdictValueWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/signals/{id}/feedback", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"verdict":"MAYBE"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
  }
}
