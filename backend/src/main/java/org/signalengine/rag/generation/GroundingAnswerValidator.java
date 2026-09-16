package org.signalengine.rag.generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.context.ContextPassage;
import org.signalengine.rag.provenance.Citation;
import org.signalengine.rag.query.Query;

/**
 * The supplied {@link AnswerValidator}: a deterministic <b>structural</b> grounding check of a
 * {@link RagAnswer} against the {@link Context} it was generated from (docs/07-rag.md Section 26;
 * docs/adr/0015-rag-grounded-generator.md).
 *
 * <p>It runs independently of whichever {@link Generator} produced the answer, so grounding is
 * enforced by the pipeline and not only by an individual generator implementation. It corrects
 * rather than throws (the {@link AnswerValidator} contract): every correction is recorded in the
 * returned answer's metadata.
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>a citation whose {@code passageId} is not a passage in the context is <b>dropped</b>;
 *   <li>a citation whose {@code provenance} does not match that context passage's provenance is
 *       <b>dropped</b> (an impossible provenance reference &mdash; the context is the source of
 *       truth, and citations are otherwise rebuilt from it);
 *   <li>duplicate citations (same {@code passageId}) are collapsed to the first;
 *   <li>an {@code answered = true} answer left with <b>no</b> valid citation is <b>downgraded</b>
 *       to insufficient evidence &mdash; an unsupported claim is never passed through;
 *   <li>an {@code answered = false} answer never carries citations.
 * </ul>
 *
 * <p>This is a runtime hallucination control. It does not judge whether the answer text is
 * <i>factually</i> supported by the cited passages &mdash; that is semantic evaluation ({@link
 * org.signalengine.rag.evaluation}), a separate, later, asynchronous concern.
 */
public final class GroundingAnswerValidator implements AnswerValidator {

  public static final String IMPLEMENTATION_ID = "grounding-answer-validator";

  /** Replacement text when an answered response is downgraded for lack of valid support. */
  public static final String UNSUPPORTED_ANSWER_REPLACEMENT =
      "The retrieved context does not support a grounded answer to this question.";

  public static final String META_VALIDATOR = "answerValidator";
  public static final String META_CITATIONS_DROPPED = "groundingCitationsDropped";
  public static final String META_DUPLICATE_CITATIONS_REMOVED = "duplicateCitationsRemoved";
  public static final String META_DOWNGRADED = "downgradedToInsufficientEvidence";
  public static final String META_STRIPPED_FROM_UNSUPPORTED = "citationsStrippedFromInsufficient";

  @Override
  public RagAnswer validate(Query query, Context context, RagAnswer answer) {
    Objects.requireNonNull(query, "query must not be null");
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(answer, "answer must not be null");

    Map<String, ContextPassage> passagesById = new LinkedHashMap<>();
    for (ContextPassage passage : context.passages()) {
      passagesById.put(passage.passageId(), passage);
    }

    if (!answer.answered()) {
      return answer
          .withCitations(List.of())
          .withMetadata(metadataWith(answer, 0, 0, false, answer.citations().size()));
    }

    List<Citation> keptCitations = new ArrayList<>();
    Set<String> seenPassageIds = new LinkedHashSet<>();
    int dropped = 0;
    int duplicatesRemoved = 0;
    for (Citation citation : answer.citations()) {
      ContextPassage passage = passagesById.get(citation.passageId());
      if (passage == null || !passage.provenance().equals(citation.provenance())) {
        dropped++;
        continue;
      }
      if (!seenPassageIds.add(citation.passageId())) {
        duplicatesRemoved++;
        continue;
      }
      keptCitations.add(
          new Citation(passage.passageId(), passage.provenance(), citation.quotedText()));
    }

    boolean downgraded = keptCitations.isEmpty();
    boolean answered = !downgraded;
    String text = downgraded ? UNSUPPORTED_ANSWER_REPLACEMENT : answer.text();

    return new RagAnswer(
        answered,
        text,
        downgraded ? List.of() : keptCitations,
        metadataWith(answer, dropped, duplicatesRemoved, downgraded, 0));
  }

  @Override
  public ComponentDescriptor descriptor() {
    return new ComponentDescriptor(
        RagComponentType.ANSWER_VALIDATOR, IMPLEMENTATION_ID, "structural/v1");
  }

  private static Map<String, String> metadataWith(
      RagAnswer answer,
      int dropped,
      int duplicatesRemoved,
      boolean downgraded,
      int strippedFromUnsupported) {
    Map<String, String> metadata = new LinkedHashMap<>(answer.metadata());
    metadata.put(META_VALIDATOR, IMPLEMENTATION_ID);
    metadata.put(META_CITATIONS_DROPPED, Integer.toString(dropped));
    metadata.put(META_DUPLICATE_CITATIONS_REMOVED, Integer.toString(duplicatesRemoved));
    metadata.put(META_DOWNGRADED, Boolean.toString(downgraded));
    metadata.put(META_STRIPPED_FROM_UNSUPPORTED, Integer.toString(strippedFromUnsupported));
    return metadata;
  }
}
