package org.signalengine.infrastructure.ai;

import java.net.http.HttpClient;
import java.time.Clock;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Composition root for the AI capability foundation (docs/03-technical-spec.md Section 6.2;
 * CLAUDE.md Section 9). The application-layer port {@link AiCapabilityInvoker} is implemented by a
 * plain class wired here; nothing Spring-annotated leaks inward.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  AiFoundationProperties.class,
  AiCapabilitySuspensionProperties.class
})
class AiFoundationConfiguration {

  @Bean
  HttpClient aiHttpClient(AiFoundationProperties properties) {
    return HttpClient.newBuilder()
        // The Python service (uvicorn) speaks HTTP/1.1 only; the JDK client's
        // default HTTP/2 upgrade attempt is rejected at the protocol level.
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(properties.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Bean
  AiCapabilitySuspensionStore aiCapabilitySuspensionStore(JdbcClient jdbcClient) {
    return new AiCapabilitySuspensionStore(jdbcClient);
  }

  /**
   * The HTTP invoker, decorated so a capability whose provider configuration was refused is
   * suspended instead of being retried for every item (docs/adr/0018-pipeline-reliability.md).
   */
  @Bean
  AiCapabilityInvoker aiCapabilityInvoker(
      HttpClient aiHttpClient,
      AiFoundationProperties properties,
      AiCapabilitySuspensionStore suspensionStore,
      AiCapabilitySuspensionProperties suspensionProperties) {
    return new SuspendingAiCapabilityInvoker(
        new HttpAiCapabilityInvoker(aiHttpClient, properties),
        suspensionStore,
        suspensionProperties,
        Clock.systemUTC());
  }
}
