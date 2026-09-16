package org.signalengine.rag.generation;

import java.util.List;
import java.util.Map;
import org.signalengine.rag.provenance.Citation;

/**
 * A generated answer and its citations &mdash; an independently usable output of the pipeline.
 *
 * <p>{@code answered} is explicit: when the context does not support an answer, a generator returns
 * {@code answered = false} with an explanatory {@code text} rather than inventing one. Every {@link
 * Citation} points back to a passage that was in the {@link org.signalengine.rag.context.Context}
 * given to the generator.
 *
 * @param answered whether the context supported an answer
 * @param text the answer, or the explanation of why no answer could be grounded; never blank
 * @param citations references to the supporting context passages; never {@code null}, empty only
 *     when {@code answered} is {@code false}
 * @param metadata open, immutable metadata set by the generator or a validator (model id, prompt
 *     version, grounding verdict&hellip;); keys never blank. The core defines no keys here.
 */
public record RagAnswer(
    boolean answered, String text, List<Citation> citations, Map<String, String> metadata) {

  public RagAnswer {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
    citations = citations == null ? List.of() : List.copyOf(citations);
    if (citations.stream().anyMatch(citation -> citation == null)) {
      throw new IllegalArgumentException("citations must not contain null");
    }
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    if (metadata.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
      throw new IllegalArgumentException("metadata keys must not be blank");
    }
  }

  /** An answer grounded in the given citations. */
  public static RagAnswer answered(String text, List<Citation> citations) {
    return new RagAnswer(true, text, citations, Map.of());
  }

  /** The explicit "the context does not support an answer" outcome. */
  public static RagAnswer insufficientEvidence(String explanation) {
    return new RagAnswer(false, explanation, List.of(), Map.of());
  }

  /** Returns a copy with {@code metadata} replaced (used by validators). */
  public RagAnswer withMetadata(Map<String, String> newMetadata) {
    return new RagAnswer(answered, text, citations, newMetadata);
  }

  /** Returns a copy with {@code citations} replaced (used by citation validators). */
  public RagAnswer withCitations(List<Citation> newCitations) {
    return new RagAnswer(answered, text, newCitations, metadata);
  }
}
