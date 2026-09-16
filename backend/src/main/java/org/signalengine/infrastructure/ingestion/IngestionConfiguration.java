package org.signalengine.infrastructure.ingestion;

import java.time.Clock;
import java.time.Duration;
import org.signalengine.application.ingestion.CollectFromSourceUseCase;
import org.signalengine.application.ingestion.ContentNormalizer;
import org.signalengine.application.ingestion.DefaultCollectFromSourceUseCase;
import org.signalengine.application.ingestion.DeterministicContentNormalizer;
import org.signalengine.application.ingestion.SourceCollector;
import org.signalengine.application.ingestion.SourceCollectorRegistry;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for ingestion (docs/03-technical-spec.md Section 6.2; CLAUDE.md Section 9).
 *
 * <p>The application-layer ingestion classes are plain classes with no Spring annotations; this
 * configuration constructs them and wires their output-port beans by constructor injection. The one
 * concrete {@link SourceCollector} is {@link HttpSourceCollector}; the {@link
 * SourceCollectorRegistry} binds it to configured source types (Q1 extension point).
 *
 * <p>This class is also where the {@link OutboundAddressPinRegistry} is created and handed to
 * {@link OutboundAddressPinHolder}, the bridge the JDK-{@link java.util.ServiceLoader}-instantiated
 * {@link PinningInetAddressResolverProvider} reads. The registry is JVM-scoped and inert until the
 * collector pins a hostname (docs/adr/0017-outbound-fetch-ssrf-dns-rebinding.md).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestionProperties.class)
class IngestionConfiguration {

  @Bean
  Clock ingestionClock() {
    return Clock.systemUTC();
  }

  @Bean
  OutboundUrlValidator outboundUrlValidator(IngestionProperties properties) {
    return new OutboundUrlValidator(properties.allowedSchemes());
  }

  /**
   * The single JVM address-pin registry. Installing it into {@link OutboundAddressPinHolder} here —
   * the composition root — is the smallest bridge to the {@link java.util.ServiceLoader}-created
   * resolver provider, which exists before this context and cannot receive constructor injection.
   * The TTL is a leaked-pin backstop only; it bounds a pin to at most one fetch (connect + request)
   * plus slack.
   */
  @Bean
  OutboundAddressPinRegistry outboundAddressPinRegistry(
      IngestionProperties properties, Clock ingestionClock) {
    Duration pinTtl =
        properties.connectTimeout().plus(properties.requestTimeout()).plus(Duration.ofSeconds(5));
    OutboundAddressPinRegistry registry = new OutboundAddressPinRegistry(pinTtl, ingestionClock);
    OutboundAddressPinHolder.install(registry);
    return registry;
  }

  @Bean
  ContentNormalizer contentNormalizer() {
    return new DeterministicContentNormalizer();
  }

  @Bean
  SourceCollector httpSourceCollector(
      OutboundUrlValidator outboundUrlValidator,
      OutboundAddressPinRegistry outboundAddressPinRegistry,
      IngestionProperties properties) {
    return new HttpSourceCollector(outboundUrlValidator, outboundAddressPinRegistry, properties);
  }

  @Bean
  SourceCollectorRegistry sourceCollectorRegistry(
      IngestionProperties properties, SourceCollector httpSourceCollector) {
    return new ConfigurableSourceCollectorRegistry(
        properties.httpCollectorSourceTypes(), httpSourceCollector);
  }

  @Bean
  CollectFromSourceUseCase collectFromSourceUseCase(
      SourceRepository sourceRepository,
      SourceCollectorRegistry sourceCollectorRegistry,
      ContentNormalizer contentNormalizer,
      RawInformationItemRepository rawInformationItemRepository,
      ActivityRecordRepository activityRecordRepository,
      Clock ingestionClock) {
    return new DefaultCollectFromSourceUseCase(
        sourceRepository,
        sourceCollectorRegistry,
        contentNormalizer,
        rawInformationItemRepository,
        activityRecordRepository,
        ingestionClock);
  }
}
