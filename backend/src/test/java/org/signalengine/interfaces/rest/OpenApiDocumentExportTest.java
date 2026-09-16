package org.signalengine.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.signalengine.application.usecase.AskQuestionUseCase;
import org.signalengine.application.usecase.ManageInterestsUseCase;
import org.signalengine.application.usecase.ManageSourcesUseCase;
import org.signalengine.application.usecase.ReviewActivityUseCase;
import org.signalengine.application.usecase.ReviewAreasOfInterestUseCase;
import org.signalengine.application.usecase.ReviewRawInformationUseCase;
import org.signalengine.application.usecase.ReviewRelevantInformationUseCase;
import org.signalengine.application.usecase.ReviewSignalsUseCase;
import org.signalengine.application.usecase.SemanticSearchUseCase;
import org.signalengine.application.usecase.SubmitFeedbackUseCase;
import org.signalengine.infrastructure.config.OpenApiConfig;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Exports the real OpenAPI document springdoc generates from the actual controllers, request/
 * response DTOs, and error handling (docs/03-technical-spec.md Section 12.2;
 * docs/04-architecture.md Section 10 — the frontend's typed API client is generated from this
 * document, not hand-written).
 *
 * <p>No live database is needed: this is a {@code @WebMvcTest} web-layer slice covering every
 * controller in the application, with each application use-case port mocked. The document therefore
 * reflects the real annotated contract without booting the full application against PostgreSQL —
 * this backend's migrations require the pgvector extension, so a full boot needs a real database
 * (Testcontainers/Docker), which client-generation should not depend on.
 *
 * <p>The exported file ({@code build/openapi/openapi.json}) is the input to the frontend's {@code
 * generate:api} script (see {@code frontend/src/api/README.md}). Regenerated on every run of this
 * test, so it cannot silently go stale relative to the real contract.
 */
@WebMvcTest
@Import(OpenApiConfig.class)
@ImportAutoConfiguration({
  SpringDocConfigProperties.class,
  SpringDocConfiguration.class,
  SpringDocWebMvcConfiguration.class
})
@ActiveProfiles("test")
class OpenApiDocumentExportTest {

  private static final Path OUTPUT_FILE = Path.of("build", "openapi", "openapi.json");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ManageSourcesUseCase manageSourcesUseCase;
  @MockitoBean private ReviewAreasOfInterestUseCase reviewAreasOfInterestUseCase;
  @MockitoBean private ManageInterestsUseCase manageInterestsUseCase;
  @MockitoBean private ReviewRawInformationUseCase reviewRawInformationUseCase;
  @MockitoBean private ReviewRelevantInformationUseCase reviewRelevantInformationUseCase;
  @MockitoBean private ReviewSignalsUseCase reviewSignalsUseCase;
  @MockitoBean private SubmitFeedbackUseCase submitFeedbackUseCase;
  @MockitoBean private ReviewActivityUseCase reviewActivityUseCase;
  @MockitoBean private SemanticSearchUseCase semanticSearchUseCase;
  @MockitoBean private AskQuestionUseCase askQuestionUseCase;

  @Test
  void exportsTheOpenApiDocumentForFrontendClientGeneration() throws Exception {
    MvcResult result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
    String openApiJson = result.getResponse().getContentAsString();

    assertThat(openApiJson).contains("\"/api/v1/search\"");
    assertThat(openApiJson).contains("\"/api/v1/questions\"");

    writeToBuildOutput(openApiJson);
  }

  private static void writeToBuildOutput(String openApiJson) {
    try {
      Files.createDirectories(OUTPUT_FILE.getParent());
      Files.writeString(OUTPUT_FILE, openApiJson);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
