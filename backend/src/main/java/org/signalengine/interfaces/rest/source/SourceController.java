package org.signalengine.interfaces.rest.source;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.signalengine.application.usecase.ManageSourcesUseCase;
import org.signalengine.interfaces.rest.ApiV1;
import org.signalengine.interfaces.rest.error.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Configure the curated sources Signal Engine collects from (workflow W1). */
@Tag(name = "Sources", description = "Configure the sources Signal Engine collects from")
@RestController
@RequestMapping(SourceController.PATH)
class SourceController {

  static final String PATH = ApiV1.BASE_PATH + "/sources";

  private final ManageSourcesUseCase manageSources;

  SourceController(ManageSourcesUseCase manageSources) {
    this.manageSources = manageSources;
  }

  @Operation(summary = "Register a new, enabled source")
  @ApiResponse(responseCode = "201", description = "Source registered")
  @ApiResponse(responseCode = "400", description = "Blank type, name, or reference")
  @PostMapping
  ResponseEntity<SourceResponse> registerSource(
      @Valid @RequestBody SourceConfigurationRequest request) {
    SourceResponse response =
        SourceResponse.from(manageSources.registerSource(request.toCommand()));
    return ResponseEntity.created(URI.create(PATH + "/" + response.id())).body(response);
  }

  @Operation(summary = "List all configured sources")
  @GetMapping
  List<SourceResponse> listConfiguredSources() {
    return manageSources.listConfiguredSources().stream().map(SourceResponse::from).toList();
  }

  @Operation(summary = "Get one configured source")
  @ApiResponse(responseCode = "200", description = "Source found")
  @ApiResponse(responseCode = "404", description = "No such source")
  @GetMapping("/{sourceId}")
  SourceResponse getConfiguredSource(@PathVariable UUID sourceId) {
    return manageSources
        .findConfiguredSource(sourceId)
        .map(SourceResponse::from)
        .orElseThrow(() -> new ResourceNotFoundException("source", sourceId));
  }

  @Operation(summary = "Edit a source's configuration")
  @ApiResponse(responseCode = "200", description = "Source updated")
  @ApiResponse(responseCode = "400", description = "Blank type, name, or reference")
  @ApiResponse(responseCode = "404", description = "No such source")
  @PutMapping("/{sourceId}")
  SourceResponse updateSourceConfiguration(
      @PathVariable UUID sourceId, @Valid @RequestBody SourceConfigurationRequest request) {
    return manageSources
        .updateSourceConfiguration(sourceId, request.toCommand())
        .map(SourceResponse::from)
        .orElseThrow(() -> new ResourceNotFoundException("source", sourceId));
  }

  @Operation(summary = "Enable a source so it becomes eligible for collection")
  @ApiResponse(responseCode = "200", description = "Source enabled")
  @ApiResponse(responseCode = "404", description = "No such source")
  @PostMapping("/{sourceId}/enable")
  SourceResponse enableSource(@PathVariable UUID sourceId) {
    return manageSources
        .enableSource(sourceId)
        .map(SourceResponse::from)
        .orElseThrow(() -> new ResourceNotFoundException("source", sourceId));
  }

  @Operation(summary = "Disable a source; already-collected information is retained")
  @ApiResponse(responseCode = "200", description = "Source disabled")
  @ApiResponse(responseCode = "404", description = "No such source")
  @PostMapping("/{sourceId}/disable")
  SourceResponse disableSource(@PathVariable UUID sourceId) {
    return manageSources
        .disableSource(sourceId)
        .map(SourceResponse::from)
        .orElseThrow(() -> new ResourceNotFoundException("source", sourceId));
  }
}
