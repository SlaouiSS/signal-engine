package org.signalengine.application.ingestion;

import org.signalengine.domain.Source;

/**
 * Output port: collects the information currently available from one configured source
 * (docs/03-technical-spec.md Section 10.2; docs/08-ingestion.md Section 4).
 *
 * <p>A collector is a replaceable adapter for one source technology. It fetches and returns raw
 * content with provenance; it does not normalise, deduplicate, persist, assess relevance or
 * importance, summarise, or call an AI provider. It never throws for an expected failure (source
 * unreachable, oversized, unsafe) — it returns {@link CollectionOutcome.CollectionFailed}.
 *
 * <p>Adding a new source type is a new adapter implementing this port, with no change to the
 * ingestion orchestration (docs/03-technical-spec.md Section 21.2).
 */
public interface SourceCollector {

  CollectionOutcome collect(Source source);
}
