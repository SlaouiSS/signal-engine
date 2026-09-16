package org.signalengine.rag.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.context.ContextPassage;
import org.signalengine.rag.provenance.Citation;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;

/**
 * {@link GroundingAnswerValidator}: deterministic structural grounding &mdash; citations resolve to
 * supplied passages, duplicates collapse, an answered response with no valid support is downgraded,
 * an insufficient response never carries citations.
 */
class GroundingAnswerValidatorTest {

  private final GroundingAnswerValidator validator = new GroundingAnswerValidator();
  private final Query query = Query.of("what happened?");

  private static Provenance provenance(String passageId) {
    return new Provenance(
        "src-" + passageId,
        URI.create("https://example.test/" + passageId),
        "Title " + passageId,
        "doc-" + passageId,
        passageId,
        Map.of("author", "A. Writer"));
  }

  private static ContextPassage passage(String passageId) {
    return new ContextPassage(passageId, "text of " + passageId, provenance(passageId), Map.of());
  }

  private static Context contextOf(String... passageIds) {
    return Context.of(
        java.util.Arrays.stream(passageIds).map(GroundingAnswerValidatorTest::passage).toList());
  }

  private static Citation citationTo(String passageId) {
    return new Citation(passageId, provenance(passageId), null);
  }

  @Test
  void aGroundedAnswerWithValidCitationsPassesThroughWithProvenancePreserved() {
    Context context = contextOf("p1", "p2");
    RagAnswer answer =
        RagAnswer.answered(
            "Beta was acquired for 1.2bn.", List.of(citationTo("p1"), citationTo("p2")));

    RagAnswer validated = validator.validate(query, context, answer);

    assertThat(validated.answered()).isTrue();
    assertThat(validated.text()).isEqualTo("Beta was acquired for 1.2bn.");
    assertThat(validated.citations()).extracting(Citation::passageId).containsExactly("p1", "p2");
    assertThat(validated.citations().get(0).provenance()).isEqualTo(provenance("p1"));
    assertThat(validated.metadata())
        .containsEntry(GroundingAnswerValidator.META_CITATIONS_DROPPED, "0")
        .containsEntry(GroundingAnswerValidator.META_DUPLICATE_CITATIONS_REMOVED, "0")
        .containsEntry(GroundingAnswerValidator.META_DOWNGRADED, "false")
        .containsEntry(GroundingAnswerValidator.META_VALIDATOR, "grounding-answer-validator");
  }

  @Test
  void anInsufficientEvidenceAnswerIsLeftAsIs() {
    RagAnswer answer = RagAnswer.insufficientEvidence("The context does not cover this.");

    RagAnswer validated = validator.validate(query, contextOf("p1"), answer);

    assertThat(validated.answered()).isFalse();
    assertThat(validated.citations()).isEmpty();
    assertThat(validated.text()).isEqualTo("The context does not cover this.");
    assertThat(validated.metadata())
        .containsEntry(GroundingAnswerValidator.META_STRIPPED_FROM_UNSUPPORTED, "0");
  }

  @Test
  void aCitationToAPassageOutsideTheContextIsDropped() {
    Context context = contextOf("p1", "p2");
    RagAnswer answer =
        RagAnswer.answered("grounded on p1", List.of(citationTo("p1"), citationTo("p9")));

    RagAnswer validated = validator.validate(query, context, answer);

    assertThat(validated.answered()).isTrue();
    assertThat(validated.citations()).extracting(Citation::passageId).containsExactly("p1");
    assertThat(validated.metadata())
        .containsEntry(GroundingAnswerValidator.META_CITATIONS_DROPPED, "1");
  }

  @Test
  void anAnsweredResponseLeftWithNoValidCitationIsDowngradedToInsufficientEvidence() {
    Context context = contextOf("p1");
    RagAnswer answer =
        RagAnswer.answered("this claim rests on nothing supplied", List.of(citationTo("p9")));

    RagAnswer validated = validator.validate(query, context, answer);

    assertThat(validated.answered()).isFalse();
    assertThat(validated.citations()).isEmpty();
    assertThat(validated.text()).isEqualTo(GroundingAnswerValidator.UNSUPPORTED_ANSWER_REPLACEMENT);
    assertThat(validated.metadata())
        .containsEntry(GroundingAnswerValidator.META_DOWNGRADED, "true")
        .containsEntry(GroundingAnswerValidator.META_CITATIONS_DROPPED, "1");
  }

  @Test
  void anAnsweredResponseWithNoCitationsAtAllIsDowngraded() {
    RagAnswer answer = new RagAnswer(true, "unsupported answer", List.of(), Map.of());

    RagAnswer validated = validator.validate(query, contextOf("p1"), answer);

    assertThat(validated.answered()).isFalse();
    assertThat(validated.metadata())
        .containsEntry(GroundingAnswerValidator.META_DOWNGRADED, "true");
  }

  @Test
  void duplicateCitationsToTheSamePassageAreCollapsed() {
    Context context = contextOf("p1", "p2");
    RagAnswer answer =
        RagAnswer.answered(
            "cites p1 twice", List.of(citationTo("p1"), citationTo("p2"), citationTo("p1")));

    RagAnswer validated = validator.validate(query, context, answer);

    assertThat(validated.citations()).extracting(Citation::passageId).containsExactly("p1", "p2");
    assertThat(validated.metadata())
        .containsEntry(GroundingAnswerValidator.META_DUPLICATE_CITATIONS_REMOVED, "1");
  }

  @Test
  void everyCitationIsDroppedWhenTheContextIsEmpty() {
    RagAnswer answer = RagAnswer.answered("answer", List.of(citationTo("p1")));

    RagAnswer validated = validator.validate(query, Context.of(List.of()), answer);

    assertThat(validated.answered()).isFalse();
    assertThat(validated.metadata())
        .containsEntry(GroundingAnswerValidator.META_CITATIONS_DROPPED, "1");
  }

  @Test
  void multipleValidCitationsAreAllKeptInOrder() {
    Context context = contextOf("p1", "p2", "p3");
    RagAnswer answer =
        RagAnswer.answered(
            "synthesis", List.of(citationTo("p3"), citationTo("p1"), citationTo("p2")));

    RagAnswer validated = validator.validate(query, context, answer);

    assertThat(validated.citations())
        .extracting(Citation::passageId)
        .containsExactly("p3", "p1", "p2");
  }

  @Test
  void aCitationCarryingProvenanceThatDoesNotMatchTheContextPassageIsDropped() {
    Context context = contextOf("p1");
    Citation wrongProvenance =
        new Citation("p1", Provenance.ofSource("some-other-source"), "a quote");
    RagAnswer answer = RagAnswer.answered("answer", List.of(wrongProvenance));

    RagAnswer validated = validator.validate(query, context, answer);

    assertThat(validated.answered()).isFalse();
    assertThat(validated.metadata())
        .containsEntry(GroundingAnswerValidator.META_CITATIONS_DROPPED, "1");
  }

  @Test
  void anInsufficientEvidenceAnswerThatCarriesCitationsHasThemStripped() {
    RagAnswer answer =
        new RagAnswer(
            false, "cannot answer", List.of(citationTo("p1"), citationTo("p2")), Map.of());

    RagAnswer validated = validator.validate(query, contextOf("p1", "p2"), answer);

    assertThat(validated.citations()).isEmpty();
    assertThat(validated.metadata())
        .containsEntry(GroundingAnswerValidator.META_STRIPPED_FROM_UNSUPPORTED, "2");
  }

  @Test
  void aSupportingQuoteOnAValidCitationIsPreserved() {
    Context context = contextOf("p1");
    Citation withQuote = new Citation("p1", provenance("p1"), "text of p1");
    RagAnswer answer = RagAnswer.answered("answer", List.of(withQuote));

    RagAnswer validated = validator.validate(query, context, answer);

    assertThat(validated.citations().get(0).quotedText()).isEqualTo("text of p1");
  }

  @Test
  void validationIsDeterministic() {
    Context context = contextOf("p1", "p2");
    RagAnswer answer =
        RagAnswer.answered(
            "answer",
            List.of(citationTo("p1"), citationTo("p9"), citationTo("p1"), citationTo("p2")));

    RagAnswer first = validator.validate(query, context, answer);
    RagAnswer second = validator.validate(query, context, answer);

    assertThat(second).isEqualTo(first);
  }

  @Test
  void nullArgumentsAreRejected() {
    Context context = contextOf("p1");
    RagAnswer answer = RagAnswer.insufficientEvidence("n/a");
    assertThatThrownBy(() -> validator.validate(null, context, answer))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> validator.validate(query, null, answer))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> validator.validate(query, context, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theDescriptorIdentifiesAStructuralAnswerValidator() {
    assertThat(validator.descriptor().type()).isEqualTo(RagComponentType.ANSWER_VALIDATOR);
    assertThat(validator.descriptor().implementationId()).isEqualTo("grounding-answer-validator");
  }
}
