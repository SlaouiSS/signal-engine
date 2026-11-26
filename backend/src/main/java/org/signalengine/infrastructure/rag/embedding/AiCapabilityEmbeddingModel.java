package org.signalengine.infrastructure.rag.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.signalengine.application.ai.AiCapabilityFailureException;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.ai.AiInputLimits;
import org.signalengine.application.ai.AiResponseMetadata;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.embedding.EmbeddingException;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;

/**
 * The RAG core {@link EmbeddingModel}, backed by the {@code embed} Python capability reached
 * through the Task 6A {@link AiCapabilityInvoker}
 * (docs/adr/0011-embedding-contract-and-local-model.md).
 *
 * <p>The Ollama / NVIDIA / other runtime and the concrete model live in the Python service's
 * configuration; this class never names one. It validates the response against the request (one
 * vector per text) and, when configured, against an expected dimension; a typed capability failure
 * or an invalid response becomes an {@link EmbeddingException}.
 *
 * <p>Immutable and thread-safe.
 */
public final class AiCapabilityEmbeddingModel implements EmbeddingModel {

  /** The Python capability that runs the configured embedding model. */
  public static final String DEFAULT_CAPABILITY = "embed";

  public static final int DEFAULT_CONTRACT_VERSION = 1;

  private static final String IMPLEMENTATION_ID = "ai-capability-embedding-model";

  /** Keeps a call's body well under the AI service's request size limit, even fully escaped. */
  private static final int MAX_CHARACTERS_PER_CALL = 1_000_000;

  private final AiCapabilityInvoker invoker;
  private final String capability;
  private final int contractVersion;
  private final int expectedDimension;
  private final ComponentDescriptor descriptor;

  /**
   * @param expectedDimension the dimension every returned vector must have; {@code 0} disables the
   *     cross-check and trusts the dimension the Python service reports
   */
  public AiCapabilityEmbeddingModel(
      AiCapabilityInvoker invoker, String capability, int contractVersion, int expectedDimension) {
    if (invoker == null) {
      throw new IllegalArgumentException("invoker must not be null");
    }
    if (capability == null || capability.isBlank()) {
      throw new IllegalArgumentException("capability must not be blank");
    }
    if (expectedDimension < 0) {
      throw new IllegalArgumentException("expectedDimension must not be negative");
    }
    this.invoker = invoker;
    this.capability = capability;
    this.contractVersion = contractVersion;
    this.expectedDimension = expectedDimension;
    this.descriptor =
        new ComponentDescriptor(
            RagComponentType.EMBEDDING_MODEL,
            IMPLEMENTATION_ID,
            capability
                + "/v"
                + contractVersion
                + (expectedDimension > 0 ? ";dim=" + expectedDimension : ""));
  }

  @Override
  public ComponentDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public EmbeddingResult embed(EmbeddingRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (request.isEmpty()) {
      return EmbeddingResult.empty(EmbeddingModelDescriptor.unspecified());
    }
    requireEveryTextWithinTheLimit(request.texts());

    List<EmbeddingResult> batches =
        batchesOf(request.texts()).stream().map(texts -> embedBatch(texts, request)).toList();
    return batches.size() == 1 ? batches.getFirst() : combine(batches);
  }

  /** A text the capability is certain to refuse is neither sent nor shortened. */
  private void requireEveryTextWithinTheLimit(List<String> texts) {
    for (String text : texts) {
      AiInputLimits.check(capability, "a text to embed", text, AiInputLimits.EMBED_TEXT)
          .ifPresent(
              error -> {
                throw failure(error);
              });
    }
  }

  /**
   * Splits the <em>list</em> of texts, never a text, into calls within the capability's limit on
   * the number of texts and small enough for its request size limit.
   */
  private static List<List<String>> batchesOf(List<String> texts) {
    List<List<String>> batches = new ArrayList<>();
    List<String> batch = new ArrayList<>();
    int characters = 0;
    for (String text : texts) {
      if (!batch.isEmpty()
          && (batch.size() == AiInputLimits.EMBED_TEXTS_PER_CALL
              || characters + text.length() > MAX_CHARACTERS_PER_CALL)) {
        batches.add(batch);
        batch = new ArrayList<>();
        characters = 0;
      }
      batch.add(text);
      characters += text.length();
    }
    batches.add(batch);
    return batches;
  }

  private EmbeddingResult embedBatch(List<String> texts, EmbeddingRequest request) {
    AiCapabilityOutcome<EmbedResult> outcome =
        invoker.invoke(
            AiCapabilityRequest.of(
                capability,
                contractVersion,
                new EmbedPayload(texts, request.role().name().toLowerCase(Locale.ROOT))),
            EmbedResult.class);

    return switch (outcome) {
      case AiCapabilityOutcome.Produced<EmbedResult> produced ->
          toResult(produced.result(), produced.metadata(), texts.size());
      case AiCapabilityOutcome.Failed<EmbedResult> failed -> throw failure(failed.error());
    };
  }

  private static EmbeddingException failure(AiError error) {
    return new EmbeddingException(
        "the embed capability failed [" + error.code() + "]: " + error.message(),
        new AiCapabilityFailureException(error));
  }

  private static EmbeddingResult combine(List<EmbeddingResult> batches) {
    EmbeddingResult first = batches.getFirst();
    List<float[]> vectors = new ArrayList<>();
    for (EmbeddingResult batch : batches) {
      if (!batch.model().equals(first.model())) {
        throw new EmbeddingException("the embedding model changed between two calls");
      }
      for (int index = 0; index < batch.count(); index++) {
        vectors.add(batch.vector(index));
      }
    }
    return new EmbeddingResult(vectors, first.dimension(), first.model());
  }

  private EmbeddingResult toResult(EmbedResult result, AiResponseMetadata metadata, int textCount) {
    if (result.vectors() == null || result.dimension() <= 0) {
      throw new EmbeddingException("the embed capability returned no usable vectors");
    }
    if (result.vectors().length != textCount) {
      throw new EmbeddingException(
          "the embed capability returned "
              + result.vectors().length
              + " vectors for "
              + textCount
              + " texts");
    }
    if (expectedDimension > 0 && result.dimension() != expectedDimension) {
      throw new EmbeddingException(
          "the embed capability returned dimension "
              + result.dimension()
              + ", expected "
              + expectedDimension);
    }
    String model =
        result.model() != null && !result.model().isBlank() ? result.model() : metadata.model();
    EmbeddingModelDescriptor modelDescriptor =
        new EmbeddingModelDescriptor(
            metadata.provider(), model, metadata.promptVersion(), result.dimension());
    try {
      return new EmbeddingResult(List.of(result.vectors()), result.dimension(), modelDescriptor);
    } catch (IllegalArgumentException invalid) {
      throw new EmbeddingException("the embed capability returned an invalid vector", invalid);
    }
  }

  /** Capability payload: the texts and their role ({@code "query"} / {@code "passage"}). */
  public record EmbedPayload(List<String> texts, String role) {}

  /**
   * Capability result: one vector per text, plus the dimension and the model that produced them.
   */
  public record EmbedResult(float[][] vectors, int dimension, String model) {}
}
