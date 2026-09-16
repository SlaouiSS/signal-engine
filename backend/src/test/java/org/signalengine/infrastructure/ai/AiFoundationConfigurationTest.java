package org.signalengine.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiFoundationConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(AiFoundationConfiguration.class);

  @Test
  void wiresTheHttpInvokerAsTheAiCapabilityPort() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(AiCapabilityInvoker.class);
          assertThat(context.getBean(AiCapabilityInvoker.class))
              .isInstanceOf(HttpAiCapabilityInvoker.class);
          assertThat(context).hasSingleBean(HttpClient.class);
        });
  }

  @Test
  void bindsAiPropertiesFromConfiguration() {
    contextRunner
        .withPropertyValues(
            "signal-engine.ai.agents-base-url=http://agents:8100",
            "signal-engine.ai.request-timeout=45s")
        .run(
            context -> {
              AiFoundationProperties properties = context.getBean(AiFoundationProperties.class);
              assertThat(properties.agentsBaseUrl().toString()).isEqualTo("http://agents:8100");
              assertThat(properties.requestTimeout().toSeconds()).isEqualTo(45);
            });
  }
}
