package org.signalengine.application.signal;

import java.util.List;
import org.signalengine.application.ai.AiError;

/**
 * Output port: produce a concise, strictly source-grounded summary of a Signal's underlying content
 * (docs/06-ai-agents.md Section 4.5; docs/02-functional-spec.md Section 8). The caller supplies
 * only the source-grounded content and its provenance context — the capability has no database or
 * web access. Returns the summary text and grounding notes, or a typed reason it could not be
 * generated. A failed generation is never turned into a fabricated Summary.
 */
public interface SummaryGenerator {

  SummaryOutcome generate(Request request);

  /** The content to summarise, plus source references shown for provenance context only. */
  record Request(String content, List<SourceReference> sources) {}

  record SourceReference(String name, String url) {}

  sealed interface SummaryOutcome {

    record Generated(String summaryText, String groundingNotes) implements SummaryOutcome {}

    record GenerationUnavailable(AiError error) implements SummaryOutcome {}
  }
}
