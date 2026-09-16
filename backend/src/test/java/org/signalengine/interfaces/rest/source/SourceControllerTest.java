package org.signalengine.interfaces.rest.source;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.usecase.ManageSourcesUseCase;
import org.signalengine.application.usecase.SourceConfiguration;
import org.signalengine.domain.Source;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SourceController.class)
@ActiveProfiles("test")
class SourceControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ManageSourcesUseCase manageSources;

  @Test
  void registersASourceAndReturns201WithLocation() throws Exception {
    Source registered = source(UUID.randomUUID(), true);
    when(manageSources.registerSource(any())).thenReturn(registered);

    mockMvc
        .perform(
            post("/api/v1/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"type":"rss","name":"Example","reference":"https://example.test/feed"}"""))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/sources/" + registered.id()))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.id").value(registered.id().toString()))
        .andExpect(jsonPath("$.enabled").value(true));

    verify(manageSources)
        .registerSource(new SourceConfiguration("rss", "Example", "https://example.test/feed"));
  }

  @Test
  void rejectsABlankFieldWith400ProblemAndDoesNotCallTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"type":"rss","name":"  ","reference":"https://example.test"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());

    verify(manageSources, never()).registerSource(any());
  }

  @Test
  void listsSources() throws Exception {
    when(manageSources.listConfiguredSources())
        .thenReturn(List.of(source(UUID.randomUUID(), true)));

    mockMvc
        .perform(get("/api/v1/sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].type").value("rss"));
  }

  @Test
  void returns404ProblemForAnUnknownSource() throws Exception {
    UUID unknownId = UUID.randomUUID();
    when(manageSources.findConfiguredSource(unknownId)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/sources/{id}", unknownId))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        .andExpect(jsonPath("$.detail").value("No source with id '" + unknownId + "'"));
  }

  @Test
  void disablingAnUnknownSourceReturns404() throws Exception {
    UUID unknownId = UUID.randomUUID();
    when(manageSources.disableSource(unknownId)).thenReturn(Optional.empty());

    mockMvc
        .perform(post("/api/v1/sources/{id}/disable", unknownId))
        .andExpect(status().isNotFound());
  }

  @Test
  void updatesASourceConfiguration() throws Exception {
    UUID id = UUID.randomUUID();
    when(manageSources.updateSourceConfiguration(eq(id), any()))
        .thenReturn(
            Optional.of(
                new Source(
                    id,
                    "atom",
                    "Renamed",
                    "https://example.test/x",
                    true,
                    Instant.now(),
                    Instant.now())));

    mockMvc
        .perform(
            put("/api/v1/sources/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"type":"atom","name":"Renamed","reference":"https://example.test/x"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"));
  }

  private static Source source(UUID id, boolean enabled) {
    return new Source(
        id, "rss", "Example", "https://example.test/feed", enabled, Instant.now(), Instant.now());
  }
}
