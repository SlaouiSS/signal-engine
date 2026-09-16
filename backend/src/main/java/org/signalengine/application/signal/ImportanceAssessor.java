package org.signalengine.application.signal;

import java.util.Set;
import org.signalengine.application.ai.AiError;

/**
 * Output port: is this <em>relevant</em> information important enough to bring to the user's
 * attention as a Signal? (docs/06-ai-agents.md Section 4.4; docs/02-functional-spec.md Section
 * 7.6). Kept strictly separate from relevance. Returns a bounded verdict — important-enough
 * (boolean) plus a reason — or a typed reason it could not be assessed. No score, no ranking; the
 * signal-selection criteria remain open (Q4 / T16). Creating the Signal is a Java state transition.
 */
public interface ImportanceAssessor {

  ImportanceVerdict assess(Query query);

  /** The item text plus its relevance context (why it was found relevant, and where). */
  record Query(String itemText, String relevanceReason, Set<String> matchedAreaCodes) {}

  sealed interface ImportanceVerdict {

    record Assessed(boolean importantEnough, String reason) implements ImportanceVerdict {}

    record AssessmentUnavailable(AiError error) implements ImportanceVerdict {}
  }
}
