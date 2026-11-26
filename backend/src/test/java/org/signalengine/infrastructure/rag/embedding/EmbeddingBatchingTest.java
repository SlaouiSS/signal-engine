package org.signalengine.infrastructure.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ai.AiCapabilityFailureException;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.ai.AiResponseMetadata;
import org.signalengine.rag.embedding.EmbeddingException;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;

/** Many texts are embedded in several calls within the capability's limits, in their order. */
class EmbeddingBatchingTest {

  private final List<List<String>> calls = new ArrayList<>();

  /** Answers one two-dimensional vector per text, whose first component is the text's number. */
  private final AiCapabilityInvoker invoker =
      new AiCapabilityInvoker() {
        @Override
        @SuppressWarnings("unchecked")
        public <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> type) {
          List<String> texts =
              ((AiCapabilityEmbeddingModel.EmbedPayload) request.payload()).texts();
          calls.add(texts);
          float[][] vectors =
              texts.stream()
                  .map(text -> new float[] {Float.parseFloat(text.split(" ")[0]), 1f})
                  .toArray(float[][]::new);
          return new AiCapabilityOutcome.Produced<>(
              (R) new AiCapabilityEmbeddingModel.EmbedResult(vectors, 2, "model"),
              new AiResponseMetadata("test", "model", "embed/v1", 1L),
              request.correlationId());
        }
      };

  private final AiCapabilityEmbeddingModel model =
      new AiCapabilityEmbeddingModel(invoker, "embed", 1, 2);

  private static List<String> texts(int count, int padding) {
    return IntStream.range(0, count).mapToObj(i -> i + " " + "x".repeat(padding)).toList();
  }

  @Test
  void sixHundredTextsAreSentInCallsOfAtMost256AndKeepTheirOrder() {
    EmbeddingResult result = model.embed(EmbeddingRequest.forPassages(texts(600, 5)));

    assertThat(calls).extracting(List::size).containsExactly(256, 256, 88);
    assertThat(result.count()).isEqualTo(600);
    assertThat(result.vector(0)[0]).isEqualTo(0f);
    assertThat(result.vector(599)[0]).isEqualTo(599f);
  }

  @Test
  void largeTextsAreAlsoSplitByTheirTotalSizeAndNoTextIsEverCut() {
    List<String> texts = texts(60, 39_000);

    model.embed(EmbeddingRequest.forPassages(texts));

    assertThat(calls).allSatisfy(call -> assertThat(call.size()).isLessThanOrEqualTo(25));
    assertThat(calls.stream().flatMap(List::stream).toList()).isEqualTo(texts);
  }

  @Test
  void aSingleTextOverTheLimitIsRefusedBeforeAnyCall() {
    List<String> texts = List.of("0 short", "1 " + "x".repeat(40_000));

    assertThatThrownBy(() -> model.embed(EmbeddingRequest.forPassages(texts)))
        .isInstanceOf(EmbeddingException.class)
        .satisfies(
            failure ->
                assertThat(
                        AiCapabilityFailureException.findIn((RuntimeException) failure)
                            .map(AiError::rejectsThisInput))
                    .contains(true));
    assertThat(calls).isEmpty();
  }
}
