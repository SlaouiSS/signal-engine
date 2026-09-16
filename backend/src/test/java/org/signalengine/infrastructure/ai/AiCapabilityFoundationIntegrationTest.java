package org.signalengine.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * The whole Java-&gt;Python path against the real agents service in a container, with the scripted
 * provider selected so no LLM is needed: JDK HTTP client -&gt; FastAPI route -&gt; envelope
 * validation -&gt; echo capability -&gt; structured-output validation -&gt; response envelope -&gt;
 * Java validation. Tagged {@code integration} (Docker required).
 */
@Tag("integration")
class AiCapabilityFoundationIntegrationTest {

  record EchoPayload(String text, String note) {}

  record EchoResult(String echoed, int characterCount) {}

  private static final GenericContainer<?> AGENTS = startedAgentsService();

  @SuppressWarnings("resource")
  private static GenericContainer<?> startedAgentsService() {
    GenericContainer<?> container =
        new GenericContainer<>(
                new ImageFromDockerfile("signal-engine-agents-it", false)
                    .withFileFromPath(".", Path.of("..", "agents")))
            .withExposedPorts(8100)
            .withEnv("AGENTS_LLM_PROVIDER", "fake")
            .withEnv(
                "AGENTS_FAKE_LLM_RESPONSES",
                "[\"{\\\"echoed\\\": \\\"ping pong\\\", \\\"characterCount\\\": 9}\","
                    + " \"{\\\"echoed\\\": \\\"ping pong\\\", \\\"characterCount\\\": 9}\"]")
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));
    container.start();
    return container;
  }

  private HttpAiCapabilityInvoker invoker() {
    URI baseUri = URI.create("http://" + AGENTS.getHost() + ":" + AGENTS.getMappedPort(8100));
    return new HttpAiCapabilityInvoker(
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
        new AiFoundationProperties(baseUri, Duration.ofSeconds(20), Duration.ofSeconds(5)));
  }

  @Test
  void callsTheRealEchoCapabilityEndToEnd() {
    UUID correlationId = UUID.randomUUID();

    var outcome =
        invoker()
            .invoke(
                new AiCapabilityRequest(
                    "echo", 1, new EchoPayload("ping pong", null), correlationId),
                EchoResult.class);

    assertThat(outcome).isInstanceOf(Produced.class);
    Produced<EchoResult> produced = (Produced<EchoResult>) outcome;
    assertThat(produced.result()).isEqualTo(new EchoResult("ping pong", 9));
    assertThat(produced.correlationId()).isEqualTo(correlationId);
    assertThat(produced.metadata().promptVersion()).isEqualTo("echo/v1");
    assertThat(produced.metadata().provider()).isEqualTo("scripted");
  }

  @Test
  void realServiceRejectsAnInvalidPayloadAsANonRetryableRequestError() {
    var outcome =
        invoker()
            .invoke(
                new AiCapabilityRequest("echo", 1, new EchoPayload("", null), UUID.randomUUID()),
                EchoResult.class);

    assertThat(outcome).isInstanceOf(Failed.class);
    Failed<EchoResult> failed = (Failed<EchoResult>) outcome;
    assertThat(failed.error().code()).isEqualTo("AI_REQUEST_INVALID");
    assertThat(failed.error().retryable()).isFalse();
  }
}
