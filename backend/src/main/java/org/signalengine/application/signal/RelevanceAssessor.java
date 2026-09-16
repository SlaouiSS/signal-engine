package org.signalengine.application.signal;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.signalengine.application.ai.AiError;

/**
 * Output port: does this information relate to the user's configured areas and interests?
 * (docs/06-ai-agents.md Section 4.3; docs/02-functional-spec.md Section 7.5). Returns a bounded
 * verdict — relevant (boolean), a short reason, and the matched area codes / interest ids — or a
 * typed reason it could not be assessed. Retaining and enriching the Relevant Information record as
 * a consequence is a Java decision.
 */
public interface RelevanceAssessor {

  RelevanceVerdict assess(Query query);

  /** The item text plus the area catalogue and the user's interests to match it against. */
  record Query(String itemText, List<AreaContext> areas, List<InterestContext> interests) {}

  record AreaContext(String code, String name) {}

  record InterestContext(UUID id, String areaCode, String description) {}

  sealed interface RelevanceVerdict {

    record Assessed(
        boolean relevant, String reason, Set<String> matchedAreaCodes, Set<UUID> matchedInterestIds)
        implements RelevanceVerdict {}

    record AssessmentUnavailable(AiError error) implements RelevanceVerdict {}
  }
}
