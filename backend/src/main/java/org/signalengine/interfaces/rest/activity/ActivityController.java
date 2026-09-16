package org.signalengine.interfaces.rest.activity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.signalengine.application.usecase.ReviewActivityUseCase;
import org.signalengine.interfaces.rest.ApiV1;
import org.signalengine.interfaces.rest.error.ResourceNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browse what the system did — the bounded activity feed (workflow W11).
 *
 * <p>{@code limit} constraints are enforced by Spring's built-in controller method validation
 * (Spring Framework 6.1+), which produces a {@code 400} problem response on violation.
 */
@Tag(name = "Activity", description = "Browse the system activity feed")
@RestController
@RequestMapping(ActivityController.PATH)
class ActivityController {

  static final String PATH = ApiV1.BASE_PATH + "/activity";

  private static final int DEFAULT_LIMIT = 50;

  private final ReviewActivityUseCase reviewActivity;

  ActivityController(ReviewActivityUseCase reviewActivity) {
    this.reviewActivity = reviewActivity;
  }

  @Operation(summary = "List the most recent activity, newest first")
  @ApiResponse(responseCode = "200", description = "Recent activity")
  @ApiResponse(responseCode = "400", description = "limit outside 1..200")
  @GetMapping
  List<ActivityRecordResponse> listRecentActivity(
      @RequestParam(defaultValue = "" + DEFAULT_LIMIT) @Min(1) @Max(200) int limit) {
    return reviewActivity.listRecentActivity(limit).stream()
        .map(ActivityRecordResponse::from)
        .toList();
  }

  @Operation(summary = "Get one activity record")
  @ApiResponse(responseCode = "200", description = "Record found")
  @ApiResponse(responseCode = "404", description = "No such record")
  @GetMapping("/{activityRecordId}")
  ActivityRecordResponse getActivityRecord(@PathVariable UUID activityRecordId) {
    return reviewActivity
        .findActivityRecord(activityRecordId)
        .map(ActivityRecordResponse::from)
        .orElseThrow(() -> new ResourceNotFoundException("activity record", activityRecordId));
  }
}
