package org.signalengine.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.ai.AiResponseMetadata;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.infrastructure.rag.embedding.AiCapabilityEmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingException;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Search embeds its query through the same capability and the same suspension as indexing. Against
 * a real PostgreSQL: an outage seen by the pipeline blocks search only briefly after the provider
 * is back, and calls during the outage stay few.
 */
class InputLimitAndInteractiveRecoveryIntegrationTest extends AbstractPersistenceIntegrationTest {

  private static final AiCapabilitySuspensionProperties PROPERTIES =
      new AiCapabilitySuspensionProperties(
          Duration.ofMinutes(15),
          Duration.ofHours(6),
          Duration.ofMinutes(6),
          Duration.ofHours(24),
          3,
          Duration.ofSeconds(30),
          Duration.ofMinutes(5));

  @Autowired private JdbcClient jdbcClient;

  private final MutableClock clock = new MutableClock();
  private final String capability = "embed-" + UUID.randomUUID();
  private final AtomicInteger providerCalls = new AtomicInteger();
  private volatile boolean providerDown;

  private final AiCapabilityInvoker provider =
      new AiCapabilityInvoker() {
        @Override
        @SuppressWarnings("unchecked")
        public <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> type) {
          providerCalls.incrementAndGet();
          if (providerDown) {
            return new AiCapabilityOutcome.Failed<>(
                new AiError(
                    "AI_TRANSPORT_TIMEOUT",
                    "timeout",
                    true,
                    "no answer",
                    request.correlationId(),
                    Map.of()));
          }
          return new AiCapabilityOutcome.Produced<>(
              (R) new AiCapabilityEmbeddingModel.EmbedResult(new float[][] {{1f, 0f}}, 2, "model"),
              new AiResponseMetadata("test", "model", "embed/v1", 1L),
              request.correlationId());
        }
      };

  private AiCapabilityEmbeddingModel embeddingModel() {
    return new AiCapabilityEmbeddingModel(
        new SuspendingAiCapabilityInvoker(
            provider, new AiCapabilitySuspensionStore(jdbcClient), PROPERTIES, clock),
        capability,
        1,
        2);
  }

  private EmbeddingResult search() {
    return embeddingModel().embed(EmbeddingRequest.forQuery("a user's question"));
  }

  private void pipelineSeesTheOutage() {
    providerDown = true;
    for (int content = 0; content < 3; content++) {
      assertThatThrownBy(
              () -> embeddingModel().embed(EmbeddingRequest.forPassages(java.util.List.of("p"))))
          .isInstanceOf(EmbeddingException.class);
    }
  }

  @Test
  void searchWorksAgainThirtySecondsAfterAShortPipelineOutage() {
    pipelineSeesTheOutage();
    providerDown = false;

    assertThatThrownBy(this::search).hasMessageContaining("suspended");
    clock.advance(Duration.ofSeconds(30));

    assertThat(search().count()).isEqualTo(1);
    assertThat(search().count()).isEqualTo(1);
  }

  @Test
  void aLongOutageStaysCheapAndSearchRecoversWithinTheTransientCooldown() {
    pipelineSeesTheOutage();
    for (int second = 0; second < 3_600; second += 10) {
      clock.advance(Duration.ofSeconds(10));
      assertThatThrownBy(this::search).isInstanceOf(EmbeddingException.class);
    }

    assertThat(providerCalls.get())
        .as("360 searches in an hour of outage")
        .isLessThanOrEqualTo(3 + 17);

    providerDown = false;
    clock.advance(PROPERTIES.transientMaxCooldown());
    assertThat(search().count()).isEqualTo(1);
  }

  @Test
  void aTextOverTheLimitNeitherCallsTheProviderNorTouchesTheCapability() {
    assertThatThrownBy(() -> embeddingModel().embed(EmbeddingRequest.forQuery("x".repeat(40_001))))
        .isInstanceOf(EmbeddingException.class);

    assertThat(providerCalls.get()).isZero();
    assertThat(new AiCapabilitySuspensionStore(jdbcClient).find(capability)).isEmpty();
    assertThat(search().count()).isEqualTo(1);
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-09-01T10:00:00Z"));

    void advance(Duration duration) {
      now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public Instant instant() {
      return now.get();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
