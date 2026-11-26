package org.signalengine.application.pipeline;

import java.util.List;

/**
 * Input port of the operator action that resumes work set aside after its attempts ran out
 * (docs/adr/0019-pipeline-automation.md). It processes nothing: the next pipeline run does.
 */
public interface RequeueExhaustedWorkUseCase {

  /** Everything that is set aside, including the indexing that cannot be requeued. */
  List<ExhaustedWork> findExhaustedWork();

  /**
   * Makes the recoverable work eligible again and records each requeue as activity. Permanent
   * failures are never touched, and repeating the call changes nothing.
   *
   * @return what was requeued by this call
   */
  List<ExhaustedWork> requeueRecoverableWork();
}
