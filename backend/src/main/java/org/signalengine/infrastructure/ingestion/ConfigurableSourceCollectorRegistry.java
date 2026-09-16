package org.signalengine.infrastructure.ingestion;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.signalengine.application.ingestion.SourceCollector;
import org.signalengine.application.ingestion.SourceCollectorRegistry;
import org.signalengine.domain.Source;

/**
 * Resolves a {@link SourceCollector} for a source from configuration: a source whose {@code type}
 * (case-insensitive) is listed in {@code signal-engine.ingestion.http-collector-source-types} is
 * collected by the {@link HttpSourceCollector}.
 *
 * <p>This is the extension point for the source-type catalogue (Q1), not a resolution of it. Any
 * other source type resolves to empty, and the orchestration reports {@code NO_COLLECTOR} — the
 * source is left untouched until a collector for its type is added.
 */
final class ConfigurableSourceCollectorRegistry implements SourceCollectorRegistry {

  private final Set<String> httpCollectorSourceTypes;
  private final SourceCollector httpSourceCollector;

  ConfigurableSourceCollectorRegistry(
      Set<String> httpCollectorSourceTypes, SourceCollector httpSourceCollector) {
    this.httpCollectorSourceTypes =
        httpCollectorSourceTypes.stream()
            .map(type -> type.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    this.httpSourceCollector = httpSourceCollector;
  }

  @Override
  public Optional<SourceCollector> collectorFor(Source source) {
    String type = source.type() == null ? "" : source.type().toLowerCase(Locale.ROOT);
    if (httpCollectorSourceTypes.contains(type)) {
      return Optional.of(httpSourceCollector);
    }
    return Optional.empty();
  }
}
