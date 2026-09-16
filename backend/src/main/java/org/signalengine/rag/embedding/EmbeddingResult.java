package org.signalengine.rag.embedding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The vectors an {@link EmbeddingModel} produced for one {@link EmbeddingRequest}.
 *
 * <p>The canonical constructor enforces every guarantee a caller (indexing, retrieval, a benchmark)
 * relies on, so an invalid embedding never propagates:
 *
 * <ul>
 *   <li>one vector per input text, in the same order;
 *   <li>every vector is non-empty and has exactly {@code dimension} components;
 *   <li>every component is finite &mdash; no {@code NaN}, no infinity;
 *   <li>nothing is truncated or padded to reach {@code dimension} &mdash; a mismatch is an error.
 * </ul>
 *
 * <p>For an empty request the result is empty and {@code dimension} is {@code 0}.
 *
 * @param vectors the embedding vectors, parallel to the request's texts; never {@code null}
 * @param dimension the length every vector has; {@code 0} only when {@code vectors} is empty,
 *     otherwise positive
 * @param model which model produced these vectors (provider, model id, version, dimension); never
 *     {@code null}
 */
public record EmbeddingResult(
    List<float[]> vectors, int dimension, EmbeddingModelDescriptor model) {

  public EmbeddingResult {
    if (vectors == null) {
      throw new IllegalArgumentException("vectors must not be null");
    }
    if (model == null) {
      throw new IllegalArgumentException("model descriptor must not be null");
    }
    if (vectors.isEmpty()) {
      if (dimension != 0) {
        throw new IllegalArgumentException(
            "dimension must be 0 for an empty result, was " + dimension);
      }
      vectors = List.of();
    } else {
      if (dimension <= 0) {
        throw new IllegalArgumentException("dimension must be positive, was " + dimension);
      }
      if (model.dimension() > 0 && model.dimension() != dimension) {
        throw new IllegalArgumentException(
            "dimension "
                + dimension
                + " disagrees with the model descriptor dimension "
                + model.dimension());
      }
      List<float[]> copies = new ArrayList<>(vectors.size());
      for (int i = 0; i < vectors.size(); i++) {
        float[] vector = vectors.get(i);
        if (vector == null || vector.length == 0) {
          throw new IllegalArgumentException("vector at index " + i + " is null or empty");
        }
        if (vector.length != dimension) {
          throw new IllegalArgumentException(
              "vector at index "
                  + i
                  + " has dimension "
                  + vector.length
                  + ", expected "
                  + dimension
                  + " (not truncated or padded)");
        }
        for (int c = 0; c < vector.length; c++) {
          if (!Float.isFinite(vector[c])) {
            throw new IllegalArgumentException(
                "vector at index " + i + " component " + c + " is not finite: " + vector[c]);
          }
        }
        copies.add(vector.clone());
      }
      vectors = Collections.unmodifiableList(copies);
    }
  }

  /** An empty result &mdash; the answer to an empty request. */
  public static EmbeddingResult empty(EmbeddingModelDescriptor model) {
    return new EmbeddingResult(List.of(), 0, model);
  }

  public int count() {
    return vectors.size();
  }

  /** A defensive copy of the vector at {@code index}. */
  public float[] vector(int index) {
    return vectors.get(index).clone();
  }
}
