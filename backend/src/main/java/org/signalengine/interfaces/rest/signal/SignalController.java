package org.signalengine.interfaces.rest.signal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.signalengine.application.usecase.ReviewSignalsUseCase;
import org.signalengine.application.usecase.SubmitFeedbackUseCase;
import org.signalengine.interfaces.rest.ApiV1;
import org.signalengine.interfaces.rest.error.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open individual signals, browse the most recent ones, and give feedback on them (workflows W6,
 * W7).
 *
 * <p>{@code limit} constraints are enforced by Spring's built-in controller method validation
 * (Spring Framework 6.1+), which produces a {@code 400} problem response on violation — the same
 * convention as {@code ActivityController}.
 */
@Tag(name = "Signals", description = "Review signals and submit feedback")
@RestController
@RequestMapping(SignalController.PATH)
class SignalController {

  static final String PATH = ApiV1.BASE_PATH + "/signals";

  private static final int DEFAULT_LIMIT = 50;

  private final ReviewSignalsUseCase reviewSignals;
  private final SubmitFeedbackUseCase submitFeedback;

  SignalController(ReviewSignalsUseCase reviewSignals, SubmitFeedbackUseCase submitFeedback) {
    this.reviewSignals = reviewSignals;
    this.submitFeedback = submitFeedback;
  }

  @Operation(summary = "List the most recent signals, newest first")
  @ApiResponse(responseCode = "200", description = "Signals (possibly empty)")
  @ApiResponse(responseCode = "400", description = "limit outside 1..200")
  @GetMapping
  List<SignalResponse> listSignals(
      @RequestParam(defaultValue = "" + DEFAULT_LIMIT) @Min(1) @Max(200) int limit) {
    return reviewSignals.listSignals(limit).stream().map(SignalResponse::from).toList();
  }

  @Operation(summary = "Open one signal")
  @ApiResponse(responseCode = "200", description = "Signal found")
  @ApiResponse(responseCode = "404", description = "No such signal")
  @GetMapping("/{signalId}")
  SignalResponse getSignal(@PathVariable UUID signalId) {
    return reviewSignals
        .findSignal(signalId)
        .map(SignalResponse::from)
        .orElseThrow(() -> new ResourceNotFoundException("signal", signalId));
  }

  @Operation(
      summary = "Submit relevant / not-relevant feedback on a signal",
      description =
          "Records the feedback and moves the signal state: RELEVANT -> KEPT, "
              + "NOT_RELEVANT -> DISMISSED.")
  @ApiResponse(responseCode = "201", description = "Feedback recorded")
  @ApiResponse(responseCode = "400", description = "Missing or invalid verdict")
  @ApiResponse(responseCode = "404", description = "No such signal")
  @PostMapping("/{signalId}/feedback")
  ResponseEntity<FeedbackResponse> submitFeedback(
      @PathVariable UUID signalId, @Valid @RequestBody SubmitFeedbackRequest request) {
    FeedbackResponse response =
        submitFeedback
            .submitFeedback(signalId, request.verdict())
            .map(FeedbackResponse::from)
            .orElseThrow(() -> new ResourceNotFoundException("signal", signalId));
    return ResponseEntity.created(URI.create(PATH + "/" + signalId)).body(response);
  }
}
