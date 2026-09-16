package org.signalengine.interfaces.rest.relevantinformation;

import static org.mockito.ArgumentMatchers.anyInt;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.usecase.ReviewRawInformationUseCase;
import org.signalengine.application.usecase.ReviewRelevantInformationUseCase;
import org.signalengine.application.usecase.ReviewSignalsUseCase;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.RelevantInformation;
import org.signalengine.domain.Signal;
import org.signalengine.domain.SignalState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RelevantInformationController.class)
@ActiveProfiles("test")
class RelevantInformationControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ReviewRelevantInformationUseCase reviewRelevantInformation;
  @MockitoBean private ReviewRawInformationUseCase reviewRawInformation;
  @MockitoBean private ReviewSignalsUseCase reviewSignals;

  @Test
  void listsRecentRelevantInformationUsingTheDefaultLimit() throws Exception {
    RelevantInformation record =
        new RelevantInformation(
            UUID.randomUUID(),
            "mentions the EU AI Act",
            Set.of("AI_AND_TECHNOLOGY"),
            Set.of(),
            Instant.now(),
            Instant.now());
    when(reviewRelevantInformation.listRecent(50)).thenReturn(List.of(record));

    mockMvc
        .perform(get("/api/v1/relevant-information"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].reason").value("mentions the EU AI Act"));

    verify(reviewRelevantInformation).listRecent(50);
  }

  @Test
  void passesAnExplicitLimitThroughForTheList() throws Exception {
    when(reviewRelevantInformation.listRecent(5)).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/relevant-information").param("limit", "5"))
        .andExpect(status().isOk());

    verify(reviewRelevantInformation).listRecent(5);
  }

  @Test
  void rejectsAListLimitAboveTheMaximumWith400() throws Exception {
    mockMvc
        .perform(get("/api/v1/relevant-information").param("limit", "5000"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

    verify(reviewRelevantInformation, never()).listRecent(anyInt());
  }

  @Test
  void listsRecordsAcrossMultipleAreasWithoutFiltering() throws Exception {
    RelevantInformation marketsRecord =
        new RelevantInformation(
            UUID.randomUUID(),
            "ECB rate decision",
            Set.of("MARKETS_AND_INVESTMENT"),
            Set.of(),
            Instant.now(),
            Instant.now());
    RelevantInformation fashionRecord =
        new RelevantInformation(
            UUID.randomUUID(),
            "runway trend report",
            Set.of("FASHION_AND_CLOTHING"),
            Set.of(),
            Instant.now(),
            Instant.now());
    when(reviewRelevantInformation.listRecent(50))
        .thenReturn(List.of(marketsRecord, fashionRecord));

    mockMvc
        .perform(get("/api/v1/relevant-information"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].matchedAreaCodes[0]").value("MARKETS_AND_INVESTMENT"))
        .andExpect(jsonPath("$[1].matchedAreaCodes[0]").value("FASHION_AND_CLOTHING"));
  }

  @Test
  void listReturnsAnEmptyArrayWhenThereIsNoRelevantInformation() throws Exception {
    when(reviewRelevantInformation.listRecent(50)).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/relevant-information"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void returnsARelevantInformationRecordWithSortedMatchedAreas() throws Exception {
    UUID id = UUID.randomUUID();
    when(reviewRelevantInformation.findRelevantInformation(id))
        .thenReturn(
            Optional.of(
                new RelevantInformation(
                    id,
                    "mentions the EU AI Act",
                    Set.of("MARKETS_AND_INVESTMENT", "AI_AND_TECHNOLOGY"),
                    Set.of(),
                    Instant.now(),
                    Instant.now())));

    mockMvc
        .perform(get("/api/v1/relevant-information/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reason").value("mentions the EU AI Act"))
        .andExpect(jsonPath("$.matchedAreaCodes[0]").value("AI_AND_TECHNOLOGY"))
        .andExpect(jsonPath("$.matchedAreaCodes[1]").value("MARKETS_AND_INVESTMENT"));
  }

  @Test
  void unknownRecordReturns404() throws Exception {
    UUID unknownId = UUID.randomUUID();
    when(reviewRelevantInformation.findRelevantInformation(unknownId)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/relevant-information/{id}", unknownId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void listsContributingRawItemsForAnExistingRecord() throws Exception {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    when(reviewRelevantInformation.findRelevantInformation(id))
        .thenReturn(Optional.of(new RelevantInformation(id, null, Set.of(), Set.of(), now, now)));
    when(reviewRawInformation.listContributingRawInformationItems(id))
        .thenReturn(
            List.of(
                new RawInformationItem(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    "hash",
                    null,
                    null,
                    null,
                    null,
                    null,
                    now,
                    new ProcessingState("relevant", null, null, null, now),
                    id,
                    now,
                    now)));

    mockMvc
        .perform(get("/api/v1/relevant-information/{id}/raw-information-items", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].contentHash").value("hash"));
  }

  @Test
  void contributingRawItemsForAnUnknownRecordReturns404() throws Exception {
    UUID unknownId = UUID.randomUUID();
    when(reviewRelevantInformation.findRelevantInformation(unknownId)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/relevant-information/{id}/raw-information-items", unknownId))
        .andExpect(status().isNotFound());
  }

  @Test
  void returnsTheSignalForARelevantInformationRecord() throws Exception {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    when(reviewRelevantInformation.findRelevantInformation(id))
        .thenReturn(Optional.of(new RelevantInformation(id, null, Set.of(), Set.of(), now, now)));
    when(reviewSignals.findSignalForRelevantInformation(id))
        .thenReturn(Optional.of(new Signal(UUID.randomUUID(), id, SignalState.KEPT, now, now)));

    mockMvc
        .perform(get("/api/v1/relevant-information/{id}/signal", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("KEPT"));
  }

  @Test
  void returns404WhenARecordHasNoSignal() throws Exception {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    when(reviewRelevantInformation.findRelevantInformation(id))
        .thenReturn(Optional.of(new RelevantInformation(id, null, Set.of(), Set.of(), now, now)));
    when(reviewSignals.findSignalForRelevantInformation(id)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/relevant-information/{id}/signal", id))
        .andExpect(status().isNotFound());
  }
}
