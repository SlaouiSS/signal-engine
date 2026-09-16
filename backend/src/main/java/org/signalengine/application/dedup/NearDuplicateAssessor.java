package org.signalengine.application.dedup;

import java.util.List;
import java.util.UUID;
import org.signalengine.application.ai.AiError;

/**
 * Output port: ask the AI whether an incoming item reports the same underlying story as any of a
 * bounded set of candidate items (docs/06-ai-agents.md Section 4.1). One verdict per candidate; the
 * grouping decision that follows is Java's (docs/03-technical-spec.md Section 10.5).
 *
 * <p>Implemented on top of {@link org.signalengine.application.ai.AiCapabilityInvoker} — this port
 * exists so the grouping use case depends on a near-duplicate contract, not on the AI envelope.
 */
public interface NearDuplicateAssessor {

  NearDuplicateAssessment assess(Query query);

  /** The candidate item to place, plus the bounded comparison set Java selected. */
  record Query(String candidateText, List<Candidate> candidates) {}

  /** One already-grouped item to compare against, identified by its raw-item id. */
  record Candidate(UUID rawInformationItemId, String text) {}

  /** Either the per-candidate verdicts, or a typed reason the assessment could not be made. */
  sealed interface NearDuplicateAssessment {

    record Assessed(List<Verdict> verdicts) implements NearDuplicateAssessment {}

    record AssessmentUnavailable(AiError error) implements NearDuplicateAssessment {}
  }

  /** The AI's advisory judgement for one candidate. */
  record Verdict(UUID rawInformationItemId, boolean sameUnderlyingStory, String reason) {}
}
