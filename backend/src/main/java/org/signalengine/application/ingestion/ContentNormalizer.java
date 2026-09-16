package org.signalengine.application.ingestion;

/**
 * Deterministic preparation of collected content for downstream stages (docs/08-ingestion.md
 * Section 7; docs/02-functional-spec.md Section 7.3).
 *
 * <p>Normalisation produces a stable representation from the raw content without changing what the
 * content says, uses no AI, and never mutates the raw content. It is a replaceable step: a
 * format-aware implementation (HTML body extraction, feed parsing) can replace the default once the
 * source-type catalogue is settled (Q1).
 */
public interface ContentNormalizer {

  /** Returns a canonical form of {@code rawContent}. Never {@code null}; may be empty. */
  String normalize(String rawContent);
}
