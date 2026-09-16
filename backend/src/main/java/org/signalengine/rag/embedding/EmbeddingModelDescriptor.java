package org.signalengine.rag.embedding;

/**
 * Which embedding model produced a set of vectors, in generic terms &mdash; enough to persist an
 * embedding, to tell two models apart, and to plan a re-embedding when the model changes.
 *
 * <p>No runtime is named in the contract; these are opaque strings the concrete {@link
 * EmbeddingModel} fills in. For the Ollama-backed implementation: {@code provider = "ollama"},
 * {@code model = "embeddinggemma"}, {@code version} = the embedding-capability contract version (an
 * Ollama model exposes no semantic version of its own).
 *
 * @param provider the runtime/provider (e.g. {@code "ollama"}); never blank
 * @param model the concrete model id (e.g. {@code "embeddinggemma"}); never blank
 * @param version the model/configuration version available to the caller; never blank
 * @param dimension the vector dimension this model produces; never negative ({@code 0} = unknown,
 *     used only for an empty result)
 */
public record EmbeddingModelDescriptor(
    String provider, String model, String version, int dimension) {

  public EmbeddingModelDescriptor {
    provider = requireText(provider, "provider");
    model = requireText(model, "model");
    version = requireText(version, "version");
    if (dimension < 0) {
      throw new IllegalArgumentException("dimension must not be negative: " + dimension);
    }
  }

  /** Placeholder for an empty result, where no model actually ran. */
  public static EmbeddingModelDescriptor unspecified() {
    return new EmbeddingModelDescriptor("unspecified", "unspecified", "unspecified", 0);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
