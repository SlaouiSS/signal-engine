package org.signalengine.infrastructure.rag.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.semanticchunker.model.ChunkingRequest;
import io.github.semanticchunker.model.ModelException;
import io.github.semanticchunker.model.ModelResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.ai.AiResponseMetadata;
import org.signalengine.infrastructure.rag.chunking.AiCapabilityChunkingModel.BoundaryQuery;
import org.signalengine.infrastructure.rag.chunking.AiCapabilityChunkingModel.BoundaryResult;

/** The library {@code ChunkingModel} that forwards to the Task 6A capability path. */
class AiCapabilityChunkingModelTest {

  private final AiCapabilityInvoker invoker = mock(AiCapabilityInvoker.class);
  private final AiCapabilityChunkingModel model = new AiCapabilityChunkingModel(invoker, 8192);

  private static ChunkingRequest request() {
    return new ChunkingRequest("INSTRUCTION\n\n[unit 0]\nx\n[unit 1]\ny", null, 0.0, 64);
  }

  @SuppressWarnings("unchecked")
  private void invokerReturns(AiCapabilityOutcome<BoundaryResult> outcome) {
    when(invoker.invoke(any(AiCapabilityRequest.class), eq(BoundaryResult.class)))
        .thenReturn(outcome);
  }

  @Test
  void forwardsThePromptAndTemperatureAsTheCapabilityPayload() {
    invokerReturns(
        new AiCapabilityOutcome.Produced<>(
            new BoundaryResult("[1]"),
            new AiResponseMetadata("nvidia", "m", "semantic-chunk-boundary/v1", 10L),
            UUID.randomUUID()));

    ModelResponse response = model.execute(request());

    assertThat(response.rawText()).isEqualTo("[1]");
    ArgumentCaptor<AiCapabilityRequest> captor = ArgumentCaptor.forClass(AiCapabilityRequest.class);
    org.mockito.Mockito.verify(invoker).invoke(captor.capture(), eq(BoundaryResult.class));
    assertThat(captor.getValue().capability()).isEqualTo("semantic-chunk-boundary");
    assertThat(captor.getValue().contractVersion()).isEqualTo(1);
    BoundaryQuery payload = (BoundaryQuery) captor.getValue().payload();
    assertThat(payload.prompt()).contains("[unit 0]");
    assertThat(payload.temperature()).isEqualTo(0.0);
  }

  @Test
  void aTypedCapabilityFailureBecomesAModelException() {
    invokerReturns(
        new AiCapabilityOutcome.Failed<>(
            new AiError(
                "AI_PROVIDER_UNAVAILABLE", "provider", true, "down", UUID.randomUUID(), Map.of())));

    assertThatThrownBy(() -> model.execute(request()))
        .isInstanceOf(ModelException.class)
        .hasMessageContaining("AI_PROVIDER_UNAVAILABLE");
  }

  @Test
  void reportsTheConfiguredContextWindowAndAConservativeTokenEstimate() {
    assertThat(model.maxInputTokens()).isEqualTo(8192);
    assertThat(model.estimateTokens("")).isZero();
    assertThat(model.estimateTokens("abcdef")).isEqualTo(2); // ceil(6/3)
  }

  @Test
  void rejectsANonPositiveContextWindow() {
    assertThatThrownBy(() -> new AiCapabilityChunkingModel(invoker, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
