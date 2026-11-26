package org.signalengine.application.pipeline;

import java.util.Arrays;
import org.signalengine.domain.ProcessingState;

/**
 * A stage that can fail an item: the name recorded as the failed stage, and the Processing State an
 * item waits in for that stage — where it returns when it is requeued (docs/05-data-model.md
 * Section 14).
 */
public enum PipelineStage {
  NEAR_DUPLICATE_ASSESSMENT("near_duplicate_assessment", ProcessingState.NORMALIZED),
  RELEVANCE_ASSESSMENT("relevance_assessment", ProcessingState.DEDUPLICATED),
  IMPORTANCE_ASSESSMENT("importance_assessment", ProcessingState.RELEVANT),
  SUMMARIZATION("summarization", ProcessingState.SIGNAL_CREATED);

  private final String stageName;
  private final String waitingState;

  PipelineStage(String stageName, String waitingState) {
    this.stageName = stageName;
    this.waitingState = waitingState;
  }

  public String stageName() {
    return stageName;
  }

  public String waitingState() {
    return waitingState;
  }

  public static PipelineStage waitingIn(String processingState) {
    return Arrays.stream(values())
        .filter(stage -> stage.waitingState.equals(processingState))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("no stage waits in state " + processingState));
  }

  public static PipelineStage named(String stageName) {
    return Arrays.stream(values())
        .filter(stage -> stage.stageName.equals(stageName))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown pipeline stage " + stageName));
  }
}
