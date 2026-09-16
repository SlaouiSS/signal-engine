package org.signalengine.interfaces.rest.relevantinformation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.signalengine.application.usecase.ReviewRawInformationUseCase;
import org.signalengine.application.usecase.ReviewRelevantInformationUseCase;
import org.signalengine.application.usecase.ReviewSignalsUseCase;
import org.signalengine.interfaces.rest.ApiV1;
import org.signalengine.interfaces.rest.error.ResourceNotFoundException;
import org.signalengine.interfaces.rest.rawinformation.RawInformationItemResponse;
import org.signalengine.interfaces.rest.signal.SignalResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browse recent relevant-information records — every retained record across every area, whether or
 * not it became a Signal (docs/05-data-model.md Section 9) — inspect one, and drill through its
 * provenance: the raw items that contributed to it and the signal (if any) derived from it
 * (docs/05-data-model.md Section 4, 9, 15).
 *
 * <p>{@code limit} constraints are enforced by Spring's built-in controller method validation
 * (Spring Framework 6.1+), which produces a {@code 400} problem response on violation — the same
 * convention as {@code SignalController}/{@code ActivityController}.
 */
@Tag(name = "Relevant information", description = "Inspect relevant information and its provenance")
@RestController
@RequestMapping(RelevantInformationController.PATH)
class RelevantInformationController {

  static final String PATH = ApiV1.BASE_PATH + "/relevant-information";

  private static final int DEFAULT_LIMIT = 50;

  private final ReviewRelevantInformationUseCase reviewRelevantInformation;
  private final ReviewRawInformationUseCase reviewRawInformation;
  private final ReviewSignalsUseCase reviewSignals;

  RelevantInformationController(
      ReviewRelevantInformationUseCase reviewRelevantInformation,
      ReviewRawInformationUseCase reviewRawInformation,
      ReviewSignalsUseCase reviewSignals) {
    this.reviewRelevantInformation = reviewRelevantInformation;
    this.reviewRawInformation = reviewRawInformation;
    this.reviewSignals = reviewSignals;
  }

  @Operation(summary = "List the most recent relevant-information records, newest first")
  @ApiResponse(responseCode = "200", description = "Relevant-information records (possibly empty)")
  @ApiResponse(responseCode = "400", description = "limit outside 1..200")
  @GetMapping
  List<RelevantInformationResponse> listRelevantInformation(
      @RequestParam(defaultValue = "" + DEFAULT_LIMIT) @Min(1) @Max(200) int limit) {
    return reviewRelevantInformation.listRecent(limit).stream()
        .map(RelevantInformationResponse::from)
        .toList();
  }

  @Operation(summary = "Get one relevant-information record")
  @ApiResponse(responseCode = "200", description = "Record found")
  @ApiResponse(responseCode = "404", description = "No such record")
  @GetMapping("/{relevantInformationId}")
  RelevantInformationResponse getRelevantInformation(@PathVariable UUID relevantInformationId) {
    return reviewRelevantInformation
        .findRelevantInformation(relevantInformationId)
        .map(RelevantInformationResponse::from)
        .orElseThrow(() -> notFound(relevantInformationId));
  }

  @Operation(summary = "List the raw information items that contributed to this record")
  @ApiResponse(responseCode = "200", description = "Contributing items (possibly empty)")
  @ApiResponse(responseCode = "404", description = "No such record")
  @GetMapping("/{relevantInformationId}/raw-information-items")
  List<RawInformationItemResponse> listContributingRawInformationItems(
      @PathVariable UUID relevantInformationId) {
    requireRecordExists(relevantInformationId);
    return reviewRawInformation.listContributingRawInformationItems(relevantInformationId).stream()
        .map(RawInformationItemResponse::from)
        .toList();
  }

  @Operation(summary = "Get the signal derived from this record")
  @ApiResponse(responseCode = "200", description = "Signal found")
  @ApiResponse(responseCode = "404", description = "No such record, or no signal for it")
  @GetMapping("/{relevantInformationId}/signal")
  SignalResponse getSignalForRelevantInformation(@PathVariable UUID relevantInformationId) {
    requireRecordExists(relevantInformationId);
    return reviewSignals
        .findSignalForRelevantInformation(relevantInformationId)
        .map(SignalResponse::from)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "signal for relevant-information record", relevantInformationId));
  }

  private void requireRecordExists(UUID relevantInformationId) {
    if (reviewRelevantInformation.findRelevantInformation(relevantInformationId).isEmpty()) {
      throw notFound(relevantInformationId);
    }
  }

  private static ResourceNotFoundException notFound(UUID relevantInformationId) {
    return new ResourceNotFoundException("relevant-information record", relevantInformationId);
  }
}
