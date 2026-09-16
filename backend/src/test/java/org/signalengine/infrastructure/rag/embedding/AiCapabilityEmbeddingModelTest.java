package org.signalengine.infrastructure.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.ai.AiResponseMetadata;
import org.signalengine.infrastructure.rag.embedding.AiCapabilityEmbeddingModel.EmbedPayload;
import org.signalengine.infrastructure.rag.embedding.AiCapabilityEmbeddingModel.EmbedResult;
import org.signalengine.rag.embedding.EmbeddingException;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.embedding.TextRole;

/** The RAG embedding model over the Task 6A capability transport. */
class AiCapabilityEmbeddingModelTest {

  private final AiCapabilityInvoker invoker = mock(AiCapabilityInvoker.class);
  private final AiCapabilityEmbeddingModel model =
      new AiCapabilityEmbeddingModel(invoker, "embed", 1, 3);

  @SuppressWarnings("unchecked")
  private void invokerReturns(AiCapabilityOutcome<EmbedResult> outcome) {
    when(invoker.invoke(any(AiCapabilityRequest.class), eq(EmbedResult.class))).thenReturn(outcome);
  }

  private static AiCapabilityOutcome<EmbedResult> produced(EmbedResult result) {
    return new AiCapabilityOutcome.Produced<>(
        result, new AiResponseMetadata("ollama", "bge-m3", "embed/v1", 5L), UUID.randomUUID());
  }

  @Test
  void sendsTextsAndRoleAndMapsTheVectorsBack() {
    invokerReturns(
        produced(
            new EmbedResult(new float[][] {{0.1f, 0.2f, 0.3f}, {0.4f, 0.5f, 0.6f}}, 3, "bge-m3")));

    EmbeddingResult result =
        model.embed(new EmbeddingRequest(List.of("alpha", "bravo"), TextRole.PASSAGE));

    assertThat(result.count()).isEqualTo(2);
    assertThat(result.dimension()).isEqualTo(3);
    assertThat(result.vector(1)).containsExactly(0.4f, 0.5f, 0.6f);
    assertThat(result.model().provider()).isEqualTo("ollama");
    assertThat(result.model().model()).isEqualTo("bge-m3");
    assertThat(result.model().version()).isEqualTo("embed/v1");
    assertThat(result.model().dimension()).isEqualTo(3);

    ArgumentCaptor<AiCapabilityRequest> captor = ArgumentCaptor.forClass(AiCapabilityRequest.class);
    verify(invoker).invoke(captor.capture(), eq(EmbedResult.class));
    assertThat(captor.getValue().capability()).isEqualTo("embed");
    EmbedPayload payload = (EmbedPayload) captor.getValue().payload();
    assertThat(payload.texts()).containsExactly("alpha", "bravo");
    assertThat(payload.role()).isEqualTo("passage");
  }

  @Test
  void anEmptyRequestSkipsTheCapabilityAndReturnsAnEmptyResult() {
    EmbeddingResult result = model.embed(new EmbeddingRequest(List.of(), TextRole.QUERY));

    assertThat(result.count()).isZero();
    verifyNoInteractions(invoker);
  }

  @Test
  void aTypedCapabilityFailureBecomesAnEmbeddingException() {
    invokerReturns(
        new AiCapabilityOutcome.Failed<>(
            new AiError(
                "AI_PROVIDER_TIMEOUT", "timeout", true, "slow", UUID.randomUUID(), Map.of())));

    assertThatThrownBy(() -> model.embed(EmbeddingRequest.forQuery("q")))
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("AI_PROVIDER_TIMEOUT");
  }

  @Test
  void aDimensionOtherThanExpectedIsRejected() {
    invokerReturns(produced(new EmbedResult(new float[][] {{1f, 2f, 3f, 4f}}, 4, "other")));

    assertThatThrownBy(() -> model.embed(EmbeddingRequest.forQuery("q")))
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("expected 3");
  }

  @Test
  void aVectorCountThatDoesNotMatchTheRequestIsRejected() {
    invokerReturns(produced(new EmbedResult(new float[][] {{1f, 2f, 3f}}, 3, "bge-m3")));

    assertThatThrownBy(() -> model.embed(new EmbeddingRequest(List.of("a", "b"), TextRole.PASSAGE)))
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("1 vectors for 2 texts");
  }

  @Test
  void aNonFiniteVectorFromTheCapabilityIsRejected() {
    invokerReturns(produced(new EmbedResult(new float[][] {{1f, Float.NaN, 3f}}, 3, "bge-m3")));

    assertThatThrownBy(() -> model.embed(EmbeddingRequest.forQuery("q")))
        .isInstanceOf(EmbeddingException.class);
  }

  @Test
  void reportsAnEmbeddingModelDescriptor() {
    assertThat(model.descriptor().type())
        .isEqualTo(org.signalengine.rag.RagComponentType.EMBEDDING_MODEL);
    assertThat(model.descriptor().version()).contains("embed/v1").contains("dim=3");
  }
}
