package org.signalengine.rag;

import java.util.Objects;

/**
 * Self-description a RAG component reports about itself: its {@link RagComponentType type} and the
 * identity and version of the concrete implementation and configuration behind the contract.
 *
 * <p>This is the whole of the "config / versioning" surface the core defines. It is not a
 * configuration framework and not a feature-flag system &mdash; it exists so a finished {@link
 * org.signalengine.rag.execution.RagExecution} can say <i>which</i> retriever, reranker, generator,
 * etc. produced it, which a later evaluation or improvement step needs in order to compare runs.
 *
 * @param type which pipeline capability this component provides
 * @param implementationId stable identifier of the concrete implementation (for example {@code
 *     "pgvector-dense-retriever"}); never blank
 * @param version implementation and/or configuration version the run used (for example {@code
 *     "2026-09-07"} or {@code "1.3.0"}); never blank
 */
public record ComponentDescriptor(RagComponentType type, String implementationId, String version) {

  public ComponentDescriptor {
    Objects.requireNonNull(type, "type");
    implementationId = requireText(implementationId, "implementationId");
    version = requireText(version, "version");
  }

  /**
   * Placeholder descriptor for a component that has not been given an identity yet &mdash; the
   * default a stage contract reports until a concrete implementation overrides {@code
   * descriptor()}.
   */
  public static ComponentDescriptor unspecified(RagComponentType type) {
    return new ComponentDescriptor(type, "unspecified", "unspecified");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
