package org.signalengine.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.domain.RelevantInformation;
import org.signalengine.domain.Signal;
import org.signalengine.domain.SignalState;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end checks that a business endpoint drives the full slice — controller to input port to
 * repository adapter to PostgreSQL — for the paths the {@code @WebMvcTest} slices cannot exercise
 * (real persistence, the feedback transaction).
 */
@AutoConfigureMockMvc
class BusinessApiIntegrationTest extends AbstractPersistenceIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private RelevantInformationRepository relevantInformationRepository;
  @Autowired private SignalRepository signalRepository;

  @Test
  void registersASourceAndReadsItBack() throws Exception {
    String created =
        mockMvc
            .perform(
                post("/api/v1/sources")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"type":"rss","name":"E2E Feed","reference":"https://e2e.test/feed"}"""))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String sourceId = JsonPath.read(created, "$.id");
    mockMvc
        .perform(get("/api/v1/sources/{id}", sourceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("E2E Feed"))
        .andExpect(jsonPath("$.enabled").value(true));
  }

  @Test
  void theSixAreasOfInterestAreServed() throws Exception {
    mockMvc
        .perform(get("/api/v1/areas-of-interest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(6));
  }

  @Test
  void addingAnInterestForAnUnknownAreaIsA400InvalidInputProblem() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/interests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"areaOfInterestCode":"NOT_AN_AREA","description":"x"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @Test
  void submittingRelevantFeedbackKeepsTheSignalInTheDatabase() throws Exception {
    RelevantInformation relevantInformation =
        relevantInformationRepository.save(
            new RelevantInformation(null, "e2e", Set.of(), Set.of(), null, null));
    Signal signal =
        signalRepository.save(
            new Signal(null, relevantInformation.id(), SignalState.NEW, null, null));

    mockMvc
        .perform(
            post("/api/v1/signals/{id}/feedback", signal.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"verdict":"RELEVANT"}"""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.verdict").value("RELEVANT"));

    assertThat(signalRepository.findById(signal.id()).orElseThrow().state())
        .isEqualTo(SignalState.KEPT);
  }

  @Test
  void unknownSignalIdIsA404Problem() throws Exception {
    mockMvc
        .perform(get("/api/v1/signals/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }
}
