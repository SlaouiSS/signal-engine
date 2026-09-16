package org.signalengine.interfaces.rest.search;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.signalengine.rag.query.Query;

/**
 * Body for a semantic-search request (docs/02-functional-spec.md Section 12.3, workflow W9). {@code
 * topK} is optional; omitting it uses {@link Query#DEFAULT_TOP_K}. Area-of-interest, source, and
 * time-period filters described in W9 are not yet offered here &mdash; the retrieval layer does not
 * implement them yet (docs/07-rag.md Section 8, 24).
 */
record SearchRequest(@NotBlank String query, @Min(1) @Max(Query.MAX_TOP_K) Integer topK) {

  int topKOrDefault() {
    return topK == null ? Query.DEFAULT_TOP_K : topK;
  }
}
