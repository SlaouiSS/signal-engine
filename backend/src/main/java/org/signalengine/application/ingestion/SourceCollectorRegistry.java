package org.signalengine.application.ingestion;

import java.util.Optional;
import org.signalengine.domain.Source;

/**
 * Output port: resolves the {@link SourceCollector} that can collect from a given source, by its
 * type. Returns empty when no collector is registered for that source type — the extension point is
 * ready but the concrete source-type catalogue is an open question (Q1).
 *
 * <p>The ingestion orchestration depends only on this port and never on a concrete collector.
 */
public interface SourceCollectorRegistry {

  Optional<SourceCollector> collectorFor(Source source);
}
