package org.signalengine.rag.context;

import java.util.List;
import java.util.Map;

/**
 * The assembled context for a query: the ordered passages selected to answer it, plus generic
 * metadata about how the context was built.
 *
 * <p>Ordering is significant &mdash; index 0 is the passage the assembler considers most useful.
 * Provenance for every passage is preserved. This type is an independently usable output: it is
 * returned by {@link org.signalengine.rag.pipeline.RagPipeline#assembleContext} with no generator
 * involved.
 *
 * @param passages selected passages in presentation order; never {@code null}, may be empty when
 *     retrieval found nothing
 * @param metadata open, immutable metadata about assembly (budget used, strategy, passages dropped,
 *     refinement applied&hellip;); keys never blank
 */
public record Context(List<ContextPassage> passages, Map<String, String> metadata) {

  public Context {
    passages = passages == null ? List.of() : List.copyOf(passages);
    if (passages.stream().anyMatch(passage -> passage == null)) {
      throw new IllegalArgumentException("passages must not contain null");
    }
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    if (metadata.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
      throw new IllegalArgumentException("metadata keys must not be blank");
    }
  }

  public static Context of(List<ContextPassage> passages) {
    return new Context(passages, Map.of());
  }

  public boolean isEmpty() {
    return passages.isEmpty();
  }
}
