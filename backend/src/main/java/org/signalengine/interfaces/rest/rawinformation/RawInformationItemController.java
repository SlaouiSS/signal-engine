package org.signalengine.interfaces.rest.rawinformation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.signalengine.application.usecase.ReviewRawInformationUseCase;
import org.signalengine.interfaces.rest.ApiV1;
import org.signalengine.interfaces.rest.error.ResourceNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inspect one collected raw information item (docs/05-data-model.md Section 8). The items that
 * contributed to a relevant-information record are listed under that record — see {@code
 * /api/v1/relevant-information/{id}/raw-information-items}.
 */
@Tag(name = "Raw information", description = "Inspect collected raw information items")
@RestController
@RequestMapping(RawInformationItemController.PATH)
class RawInformationItemController {

  static final String PATH = ApiV1.BASE_PATH + "/raw-information-items";

  private final ReviewRawInformationUseCase reviewRawInformation;

  RawInformationItemController(ReviewRawInformationUseCase reviewRawInformation) {
    this.reviewRawInformation = reviewRawInformation;
  }

  @Operation(summary = "Get one raw information item")
  @ApiResponse(responseCode = "200", description = "Item found")
  @ApiResponse(responseCode = "404", description = "No such item")
  @GetMapping("/{rawInformationItemId}")
  RawInformationItemResponse getRawInformationItem(@PathVariable UUID rawInformationItemId) {
    return reviewRawInformation
        .findRawInformationItem(rawInformationItemId)
        .map(RawInformationItemResponse::from)
        .orElseThrow(
            () -> new ResourceNotFoundException("raw information item", rawInformationItemId));
  }
}
