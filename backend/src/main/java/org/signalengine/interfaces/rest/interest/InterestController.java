package org.signalengine.interfaces.rest.interest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.signalengine.application.usecase.ManageInterestsUseCase;
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

/** Configure the interests that sharpen relevance within an area (workflow W2). */
@Tag(name = "Interests", description = "Configure the interests that sharpen relevance")
@RestController
@RequestMapping(InterestController.PATH)
class InterestController {

  static final String PATH = ApiV1.BASE_PATH + "/interests";

  private final ManageInterestsUseCase manageInterests;

  InterestController(ManageInterestsUseCase manageInterests) {
    this.manageInterests = manageInterests;
  }

  @Operation(summary = "Add a new, enabled interest under an area of interest")
  @ApiResponse(responseCode = "201", description = "Interest added")
  @ApiResponse(responseCode = "400", description = "Blank description or unknown area code")
  @PostMapping
  ResponseEntity<InterestResponse> addInterest(@Valid @RequestBody AddInterestRequest request) {
    InterestResponse response =
        InterestResponse.from(
            manageInterests.addInterest(request.areaOfInterestCode(), request.description()));
    return ResponseEntity.created(URI.create(PATH + "/" + response.id())).body(response);
  }

  @Operation(summary = "List all configured interests")
  @GetMapping
  List<InterestResponse> listInterests() {
    return manageInterests.listInterests().stream().map(InterestResponse::from).toList();
  }

  @Operation(summary = "Get one interest")
  @ApiResponse(responseCode = "200", description = "Interest found")
  @ApiResponse(responseCode = "404", description = "No such interest")
  @GetMapping("/{interestId}")
  InterestResponse getInterest(@PathVariable UUID interestId) {
    return manageInterests
        .findInterest(interestId)
        .map(InterestResponse::from)
        .orElseThrow(() -> new ResourceNotFoundException("interest", interestId));
  }

  @Operation(summary = "Edit an interest's description")
  @ApiResponse(responseCode = "200", description = "Interest updated")
  @ApiResponse(responseCode = "400", description = "Blank description")
  @ApiResponse(responseCode = "404", description = "No such interest")
  @PutMapping("/{interestId}")
  InterestResponse updateInterestDescription(
      @PathVariable UUID interestId, @Valid @RequestBody UpdateInterestDescriptionRequest request) {
    return manageInterests
        .updateInterestDescription(interestId, request.description())
        .map(InterestResponse::from)
        .orElseThrow(() -> new ResourceNotFoundException("interest", interestId));
  }

  @Operation(summary = "Enable an interest")
  @ApiResponse(responseCode = "200", description = "Interest enabled")
  @ApiResponse(responseCode = "404", description = "No such interest")
  @PostMapping("/{interestId}/enable")
  InterestResponse enableInterest(@PathVariable UUID interestId) {
    return manageInterests
        .enableInterest(interestId)
        .map(InterestResponse::from)
        .orElseThrow(() -> new ResourceNotFoundException("interest", interestId));
  }

  @Operation(summary = "Disable an interest")
  @ApiResponse(responseCode = "200", description = "Interest disabled")
  @ApiResponse(responseCode = "404", description = "No such interest")
  @PostMapping("/{interestId}/disable")
  InterestResponse disableInterest(@PathVariable UUID interestId) {
    return manageInterests
        .disableInterest(interestId)
        .map(InterestResponse::from)
        .orElseThrow(() -> new ResourceNotFoundException("interest", interestId));
  }
}
