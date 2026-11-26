package org.signalengine.application.pipeline;

import java.util.List;

/**
 * Input port: one bounded pass over the pipeline — collect, group, process, reconcile the knowledge
 * base index — in that order, since each stage consumes the previous one's output
 * (docs/adr/0019-pipeline-automation.md). A stage that fails does not stop the following ones. The
 * caller must not run two cycles at once.
 */
public interface RunPipelineCycleUseCase {

  /** The report of each stage, in the order the stages ran. */
  List<StageReport> runCycle();
}
