package org.signalengine.interfaces.rest.areaofinterest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.signalengine.application.usecase.ReviewAreasOfInterestUseCase;
import org.signalengine.interfaces.rest.ApiV1;
import org.signalengine.interfaces.rest.error.ResourceNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** View the six fixed areas of interest (docs/02-functional-spec.md Section 5.2). Read-only. */
@Tag(name = "Areas of interest", description = "The six fixed areas Signal Engine monitors")
@RestController
@RequestMapping(AreaOfInterestController.PATH)
class AreaOfInterestController {

  static final String PATH = ApiV1.BASE_PATH + "/areas-of-interest";

  private final ReviewAreasOfInterestUseCase reviewAreasOfInterest;

  AreaOfInterestController(ReviewAreasOfInterestUseCase reviewAreasOfInterest) {
    this.reviewAreasOfInterest = reviewAreasOfInterest;
  }

  @Operation(summary = "List the six areas of interest")
  @GetMapping
  List<AreaOfInterestResponse> listAreasOfInterest() {
    return reviewAreasOfInterest.listAreasOfInterest().stream()
        .map(AreaOfInterestResponse::from)
        .toList();
  }

  @Operation(summary = "Get one area of interest by its code")
  @ApiResponse(responseCode = "200", description = "Area found")
  @ApiResponse(responseCode = "404", description = "No area with that code")
  @GetMapping("/{areaOfInterestCode}")
  AreaOfInterestResponse getAreaOfInterest(@PathVariable String areaOfInterestCode) {
    return reviewAreasOfInterest
        .findAreaOfInterest(areaOfInterestCode)
        .map(AreaOfInterestResponse::from)
        .orElseThrow(() -> new ResourceNotFoundException("area of interest", areaOfInterestCode));
  }
}
