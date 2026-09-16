package org.signalengine.infrastructure.rag.chunking;

import io.github.semanticchunker.model.ChunkingModel;
import io.github.semanticchunker.model.ChunkingRequest;
import io.github.semanticchunker.model.ModelException;
import io.github.semanticchunker.model.ModelResponse;
import io.github.semanticchunker.model.TokenUsage;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityRequest;

/**
 * The {@code semantic-chunker} {@link ChunkingModel}, reaching a language model <b>only</b> through
 * the Task 6A {@link AiCapabilityInvoker} and the versioned {@code semantic-chunk-boundary} Python
 * capability (docs/adr/0006-ai-java-python-foundation.md;
 * docs/adr/0010-indexing-foundation-and-semantic-chunking.md).
 *
 * <p>The library composes the boundary prompt; this adapter forwards it unchanged and returns the
 * model's raw text for the library to validate ({@code [N1,N2,...]} or {@code []}). Provider choice
 * &mdash; NVIDIA Build now, local Ollama later &mdash; is entirely the Python service's
 * configuration; this class never names a provider.
 *
 * <p>A transport failure, a timeout, or a typed capability error becomes a {@link ModelException},
 * which the library treats as terminal (it does not retry a failure to obtain a response).
 *
 * <p>Immutable and thread-safe, as the SPI requires.
 */
public final class AiCapabilityChunkingModel implements ChunkingModel {

  /** The Python capability that runs the boundary prompt against the configured provider. */
  public static final String CAPABILITY = "semantic-chunk-boundary";

  public static final int CONTRACT_VERSION = 1;

  private final AiCapabilityInvoker invoker;
  private final int maxInputTokens;

  public AiCapabilityChunkingModel(AiCapabilityInvoker invoker, int maxInputTokens) {
    if (invoker == null) {
      throw new IllegalArgumentException("invoker must not be null");
    }
    if (maxInputTokens < 1) {
      throw new IllegalArgumentException("maxInputTokens must be positive: " + maxInputTokens);
    }
    this.invoker = invoker;
    this.maxInputTokens = maxInputTokens;
  }

  @Override
  public ModelResponse execute(ChunkingRequest request) {
    AiCapabilityOutcome<BoundaryResult> outcome =
        invoker.invoke(
            AiCapabilityRequest.of(
                CAPABILITY,
                CONTRACT_VERSION,
                new BoundaryQuery(request.prompt(), request.temperature())),
            BoundaryResult.class);

    return switch (outcome) {
      case AiCapabilityOutcome.Produced<BoundaryResult> produced ->
          // Token usage is not observable through the Task 6A provider abstraction;
          // reported as zero (docs/07-rag.md Section 21, known limitations).
          new ModelResponse(produced.result().rawText(), new TokenUsage(0, 0));
      case AiCapabilityOutcome.Failed<BoundaryResult> failed ->
          throw new ModelException(
              "the semantic-chunk-boundary capability failed ["
                  + failed.error().code()
                  + "]: "
                  + failed.error().message());
    };
  }

  @Override
  public int maxInputTokens() {
    return maxInputTokens;
  }

  @Override
  public int estimateTokens(String text) {
    // Provisional heuristic: ~3 characters per token, rounded up, so the estimate
    // over-counts rather than risking a context overflow (docs/07-rag.md Section 21).
    return text.isEmpty() ? 0 : (text.length() + 2) / 3;
  }

  /** Capability payload: the library-composed prompt and its temperature hint. */
  public record BoundaryQuery(String prompt, double temperature) {}

  /** Capability result: the model's raw text, for the library to parse and validate. */
  public record BoundaryResult(String rawText) {}
}
