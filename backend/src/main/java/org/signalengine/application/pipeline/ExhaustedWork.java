package org.signalengine.application.pipeline;

import java.util.UUID;

/**
 * One piece of work that is no longer attempted.
 *
 * @param rawInformationItemId the item concerned
 * @param stage the stage that stopped being attempted
 * @param recoverable whether requeueing it makes sense; a permanent failure never is
 * @param reason the diagnostic
 */
public record ExhaustedWork(
    UUID rawInformationItemId, String stage, boolean recoverable, String reason) {

  /** The stage name of a blocked knowledge base indexing, which is not a Processing State. */
  public static final String KNOWLEDGE_BASE_INDEXING = "knowledge_base_indexing";
}
