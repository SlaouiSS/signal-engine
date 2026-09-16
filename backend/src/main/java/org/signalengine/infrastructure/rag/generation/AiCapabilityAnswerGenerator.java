package org.signalengine.infrastructure.rag.generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome.Failed;
import org.signalengine.application.ai.AiCapabilityOutcome.Produced;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiResponseMetadata;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.context.ContextPassage;
import org.signalengine.rag.generation.GenerationException;
import org.signalengine.rag.generation.Generator;
import org.signalengine.rag.generation.RagAnswer;
import org.signalengine.rag.provenance.Citation;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;

/**
 * The RAG core {@link Generator}, backed by the {@code answer} Python capability reached through
 * the Task 6A {@link AiCapabilityInvoker} (docs/07-rag.md Section 26;
 * docs/adr/0015-rag-grounded-generator.md).
 *
 * <p>Provider- and model-independent: the concrete LLM and the runtime live in the Python service's
 * configuration; this class never names one. It supplies the query and the context passages (each
 * with its stable {@code passageId}), asks the capability for a structured answer, and builds the
 * {@link RagAnswer}.
 *
 * <p>Grounding is enforced structurally, not trusted from the model:
 *
 * <ul>
 *   <li>an <b>empty context</b> short-circuits to an insufficient-evidence answer with no AI call;
 *   <li>the model returns <b>passage ids only</b>; every {@link Citation}'s {@link Provenance} is
 *       taken from the matching {@link ContextPassage}, never from the model;
 *   <li>a citation to a passage that is not in the supplied context, an answered response with no
 *       citation, or an insufficient-evidence response that cites passages, is rejected as a {@link
 *       GenerationException} &mdash; the Python capability already validates this (with one bounded
 *       repair); this is the defensive re-check the RAG contract requires.
 * </ul>
 *
 * <p>A provider failure, a timeout, or output still invalid after the one repair all arrive as a
 * {@link Failed} outcome and become a {@link GenerationException}. The generator never retries and
 * never fabricates an answer.
 *
 * <p>Immutable and thread-safe.
 */
public final class AiCapabilityAnswerGenerator implements Generator {

  /** The Python capability that synthesises a grounded answer. */
  public static final String CAPABILITY = "answer";

  public static final int DEFAULT_CONTRACT_VERSION = 1;

  static final String INSUFFICIENT_EVIDENCE_MESSAGE =
      "The retrieved context does not contain information to answer this question.";

  private static final String IMPLEMENTATION_ID = "ai-capability-answer-generator";

  private final AiCapabilityInvoker invoker;
  private final int contractVersion;
  private final ComponentDescriptor descriptor;

  public AiCapabilityAnswerGenerator(AiCapabilityInvoker invoker) {
    this(invoker, DEFAULT_CONTRACT_VERSION);
  }

  public AiCapabilityAnswerGenerator(AiCapabilityInvoker invoker, int contractVersion) {
    if (invoker == null) {
      throw new IllegalArgumentException("invoker must not be null");
    }
    if (contractVersion < 1) {
      throw new IllegalArgumentException("contractVersion must be positive");
    }
    this.invoker = invoker;
    this.contractVersion = contractVersion;
    this.descriptor =
        new ComponentDescriptor(
            RagComponentType.GENERATOR, IMPLEMENTATION_ID, CAPABILITY + "/v" + contractVersion);
  }

  @Override
  public ComponentDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public RagAnswer generate(Query query, Context context) {
    if (query == null) {
      throw new IllegalArgumentException("query must not be null");
    }
    if (context == null) {
      throw new IllegalArgumentException("context must not be null");
    }

    if (context.isEmpty()) {
      return new RagAnswer(
          false,
          INSUFFICIENT_EVIDENCE_MESSAGE,
          List.of(),
          Map.of("generator", IMPLEMENTATION_ID, "shortCircuit", "empty-context"));
    }

    Map<String, ContextPassage> passagesById = new LinkedHashMap<>();
    List<PassagePayload> passagePayloads = new ArrayList<>();
    for (ContextPassage passage : context.passages()) {
      passagesById.put(passage.passageId(), passage);
      passagePayloads.add(
          new PassagePayload(
              passage.passageId(), passage.text(), sourceLabel(passage.provenance())));
    }

    AiCapabilityRequest request =
        AiCapabilityRequest.of(
            CAPABILITY, contractVersion, new RequestPayload(query.text(), passagePayloads));

    return switch (invoker.invoke(request, ResultPayload.class)) {
      case Failed<ResultPayload> failed ->
          throw new GenerationException(
              "the answer capability failed ["
                  + failed.error().code()
                  + "]: "
                  + failed.error().message());
      case Produced<ResultPayload> produced ->
          toAnswer(produced.result(), produced.metadata(), passagesById, produced.correlationId());
    };
  }

  private RagAnswer toAnswer(
      ResultPayload result,
      AiResponseMetadata responseMetadata,
      Map<String, ContextPassage> passagesById,
      UUID correlationId) {
    if (result == null || result.answer() == null || result.answer().isBlank()) {
      throw new GenerationException(
          "the answer capability returned no answer text (correlationId " + correlationId + ")");
    }

    List<CitationPayload> citationPayloads =
        result.citations() == null ? List.of() : result.citations();
    List<Citation> citations = new ArrayList<>();
    for (CitationPayload citationPayload : citationPayloads) {
      ContextPassage passage = passagesById.get(citationPayload.passageId());
      if (passage == null) {
        throw new GenerationException(
            "the answer capability cited passage '"
                + citationPayload.passageId()
                + "' which is not in the supplied context");
      }
      citations.add(new Citation(passage.passageId(), passage.provenance(), null));
    }

    if (result.answered() && citations.isEmpty()) {
      throw new GenerationException(
          "the answer capability marked the answer grounded but cited no passage");
    }
    if (!result.answered() && !citations.isEmpty()) {
      throw new GenerationException(
          "the answer capability cited passages for an insufficient-evidence answer");
    }

    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("generator", IMPLEMENTATION_ID);
    metadata.put("provider", responseMetadata.provider());
    metadata.put("model", responseMetadata.model());
    metadata.put("promptVersion", responseMetadata.promptVersion());
    metadata.put("groundedByModel", Boolean.toString(result.answered()));
    return new RagAnswer(result.answered(), result.answer(), citations, metadata);
  }

  /** A short, human-readable label for a passage's origin &mdash; never a fabricated URL. */
  private static String sourceLabel(Provenance provenance) {
    if (provenance.title() != null) {
      return provenance.title();
    }
    if (provenance.documentId() != null) {
      return provenance.documentId();
    }
    return provenance.sourceId();
  }

  /** Capability payload: the question and the context passages Java selected. */
  public record RequestPayload(String question, List<PassagePayload> passages) {}

  /** One context passage: its stable identifier, its text, and a plain source label. */
  public record PassagePayload(String passageId, String text, String source) {}

  /** Capability result: the answer, whether it is grounded, and which passages it cites. */
  public record ResultPayload(boolean answered, String answer, List<CitationPayload> citations) {}

  /**
   * One citation: a passage id the answer draws on. Provenance is attached by Java, not the model.
   */
  public record CitationPayload(String passageId) {}
}
