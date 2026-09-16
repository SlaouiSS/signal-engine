package org.signalengine.rag.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An information need presented to the RAG pipeline.
 *
 * <p>{@code text} is the question or search phrase. {@code topK} is how many passages retrieval
 * should return &mdash; a first-class knob so a {@link org.signalengine.rag.retrieval.Retriever}
 * never has to guess and never runs an unbounded query. {@code metadataFilters} is a generic, open
 * set of constraints a retriever may honour to narrow candidates (for example {@code "sourceId" ->
 * "acme-feed"}); the core does not define the vocabulary, and richer structured filtering (ranges,
 * sets, negation) is an open question, not modelled here. {@code attributes} is an open set of
 * per-stage hints; unknown keys are ignored.
 *
 * <p>All maps are immutable and preserve insertion order. A {@link QueryProcessor} produces a new
 * {@code Query} rather than mutating this one.
 *
 * @param text the question or search phrase; never blank
 * @param metadataFilters generic retrieval-narrowing constraints; keys never blank
 * @param attributes generic per-stage hints; keys never blank
 * @param topK how many passages to retrieve; between 1 and {@link #MAX_TOP_K}
 */
public record Query(
    String text, Map<String, String> metadataFilters, Map<String, String> attributes, int topK) {

  /**
   * The provisional default number of passages to retrieve when a caller does not specify one. Not
   * fixed by any approved specification &mdash; retrieval parameters are open (docs/07-rag.md
   * Section 7, 18); this is a working default a caller overrides per query.
   */
  public static final int DEFAULT_TOP_K = 5;

  /** The hard upper bound on {@code topK}, so a query can never scan the store unbounded. */
  public static final int MAX_TOP_K = 200;

  public Query {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
    if (topK < 1 || topK > MAX_TOP_K) {
      throw new IllegalArgumentException(
          "topK must be between 1 and " + MAX_TOP_K + ", was " + topK);
    }
    metadataFilters = copyRejectingBlankKeys(metadataFilters, "metadataFilters");
    attributes = copyRejectingBlankKeys(attributes, "attributes");
  }

  /** A query carrying only its text, with {@link #DEFAULT_TOP_K}. */
  public static Query of(String text) {
    return new Query(text, Map.of(), Map.of(), DEFAULT_TOP_K);
  }

  /** A query with an explicit {@code topK}. */
  public static Query of(String text, int topK) {
    return new Query(text, Map.of(), Map.of(), topK);
  }

  /** Returns a copy with {@code text} replaced (used by query rewriting). */
  public Query withText(String newText) {
    return new Query(newText, metadataFilters, attributes, topK);
  }

  /** Returns a copy with {@code topK} replaced. */
  public Query withTopK(int newTopK) {
    return new Query(text, metadataFilters, attributes, newTopK);
  }

  /** Returns a copy with one metadata filter added or replaced. */
  public Query withMetadataFilter(String key, String value) {
    Map<String, String> extended = new LinkedHashMap<>(metadataFilters);
    extended.put(key, value);
    return new Query(text, extended, attributes, topK);
  }

  private static Map<String, String> copyRejectingBlankKeys(
      Map<String, String> source, String name) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    Map<String, String> copy = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(name + " keys must not be blank");
          }
          copy.put(key, value);
        });
    return Collections.unmodifiableMap(copy);
  }
}
