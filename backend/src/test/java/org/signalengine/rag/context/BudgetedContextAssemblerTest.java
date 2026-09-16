package org.signalengine.rag.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.signalengine.rag.retrieval.RetrievedPassage;

/**
 * {@link BudgetedContextAssembler}: retrieval order preserved, exact-duplicate ids removed, a
 * character budget enforced by selecting whole passages, provenance and retrieval information kept,
 * source text never modified, deterministic output.
 */
class BudgetedContextAssemblerTest {

  private final Query query = Query.of("what happened?");

  private static Provenance provenance(String passageId) {
    return new Provenance(
        "src-" + passageId,
        URI.create("https://example.test/" + passageId),
        "Title of " + passageId,
        "doc-" + passageId,
        passageId,
        Map.of("author", "A. Writer"));
  }

  private static RetrievedPassage retrieved(String passageId, String text, double score) {
    return new RetrievedPassage(
        passageId, text, provenance(passageId), score, Map.of("lang", "en"));
  }

  private static RetrievalResult retrievalOf(RetrievedPassage... passages) {
    return new RetrievalResult(List.of(passages), Map.of("strategy", "pgvector-cosine-exact"));
  }

  private static String textOf(int characters) {
    return "x".repeat(characters);
  }

  @Test
  void anEmptyRetrievalResultYieldsAnEmptyContext() {
    Context context = new BudgetedContextAssembler().assemble(query, retrievalOf());

    assertThat(context.isEmpty()).isTrue();
    assertThat(context.metadata())
        .containsEntry(BudgetedContextAssembler.META_RETRIEVED_PASSAGES, "0")
        .containsEntry(BudgetedContextAssembler.META_SELECTED_PASSAGES, "0")
        .containsEntry(BudgetedContextAssembler.META_USED_CHARACTERS, "0");
  }

  @Test
  void aSinglePassageIsCarriedThroughWithItsIdTextAndProvenance() {
    RetrievedPassage passage = retrieved("p1", "the only passage", 0.91);

    Context context = new BudgetedContextAssembler().assemble(query, retrievalOf(passage));

    assertThat(context.passages()).hasSize(1);
    ContextPassage assembled = context.passages().get(0);
    assertThat(assembled.passageId()).isEqualTo("p1");
    assertThat(assembled.text()).isEqualTo("the only passage");
    assertThat(assembled.provenance()).isEqualTo(passage.provenance());
  }

  @Test
  void multiplePassagesWithinBudgetAreAllSelectedInRetrievalOrder() {
    Context context =
        new BudgetedContextAssembler()
            .assemble(
                query,
                retrievalOf(
                    retrieved("p1", "first", 0.10),
                    retrieved("p2", "second", 0.20),
                    retrieved("p3", "third", 0.30)));

    assertThat(context.passages())
        .extracting(ContextPassage::passageId)
        .containsExactly("p1", "p2", "p3");
  }

  @Test
  void retrievalOrderIsPreservedEvenWhenScoresWouldSortItDifferently() {
    // scores ascending — a score-based reorder would flip this; the assembler must not.
    Context context =
        new BudgetedContextAssembler()
            .assemble(
                query,
                retrievalOf(
                    retrieved("low", "a", 0.10),
                    retrieved("mid", "b", 0.50),
                    retrieved("high", "c", 0.99)));

    assertThat(context.passages())
        .extracting(ContextPassage::passageId)
        .containsExactly("low", "mid", "high");
    assertThat(context.metadata())
        .containsEntry(BudgetedContextAssembler.META_ORDERING_POLICY, "retrieval-order");
  }

  @Test
  void exactDuplicatePassageIdsAreRemovedKeepingTheFirstOccurrence() {
    Context context =
        new BudgetedContextAssembler()
            .assemble(
                query,
                retrievalOf(
                    retrieved("p1", "first occurrence text", 0.90),
                    retrieved("p2", "another passage", 0.80),
                    retrieved("p1", "SECOND occurrence text", 0.70)));

    assertThat(context.passages())
        .extracting(ContextPassage::passageId)
        .containsExactly("p1", "p2");
    assertThat(context.passages().get(0).text()).isEqualTo("first occurrence text");
    assertThat(context.metadata())
        .containsEntry(BudgetedContextAssembler.META_DUPLICATE_IDS_REMOVED, "1");
  }

  @Test
  void twoPassagesWithIdenticalTextButDifferentIdsAreBothKept() {
    Context context =
        new BudgetedContextAssembler()
            .assemble(
                query,
                retrievalOf(
                    retrieved("p1", "the very same sentence", 0.9),
                    retrieved("p2", "the very same sentence", 0.8)));

    assertThat(context.passages())
        .extracting(ContextPassage::passageId)
        .containsExactly("p1", "p2");
    assertThat(context.metadata())
        .containsEntry(BudgetedContextAssembler.META_DUPLICATE_IDS_REMOVED, "0");
  }

  @Test
  void provenanceAndInboundMetadataAreCarriedThroughUnchanged() {
    RetrievedPassage passage = retrieved("p1", "body", 0.5);

    ContextPassage assembled =
        new BudgetedContextAssembler().assemble(query, retrievalOf(passage)).passages().get(0);

    assertThat(assembled.provenance()).isEqualTo(passage.provenance());
    assertThat(assembled.metadata()).containsEntry("lang", "en");
  }

  @Test
  void retrievalScoreAndRankArePreservedInContextPassageMetadata() {
    Context context =
        new BudgetedContextAssembler()
            .assemble(query, retrievalOf(retrieved("p1", "a", 0.87), retrieved("p2", "b", 0.42)));

    assertThat(context.passages().get(0).metadata())
        .containsEntry(BudgetedContextAssembler.PASSAGE_META_RETRIEVAL_RANK, "0")
        .containsEntry(BudgetedContextAssembler.PASSAGE_META_RETRIEVAL_SCORE, "0.87");
    assertThat(context.passages().get(1).metadata())
        .containsEntry(BudgetedContextAssembler.PASSAGE_META_RETRIEVAL_RANK, "1")
        .containsEntry(BudgetedContextAssembler.PASSAGE_META_RETRIEVAL_SCORE, "0.42");
  }

  @Test
  void withNoDuplicatesRetrievalRankIsSimplyTheRetrievalPosition() {
    Context context =
        new BudgetedContextAssembler()
            .assemble(
                query,
                retrievalOf(
                    retrieved("p1", "a", 0.9),
                    retrieved("p2", "b", 0.8),
                    retrieved("p3", "c", 0.7)));

    assertThat(retrievalRankOf(context, 0)).isEqualTo("0");
    assertThat(retrievalRankOf(context, 1)).isEqualTo("1");
    assertThat(retrievalRankOf(context, 2)).isEqualTo("2");
  }

  @Test
  void retrievalRankIsTheOriginalRetrievalPositionNotThePositionAfterDeduplication() {
    // retrieval order: A@0, B@1, A@2 (duplicate id, dropped), C@3
    Context context =
        new BudgetedContextAssembler()
            .assemble(
                query,
                retrievalOf(
                    retrieved("A", "alpha", 0.9),
                    retrieved("B", "beta", 0.8),
                    retrieved("A", "alpha again", 0.7),
                    retrieved("C", "gamma", 0.6)));

    assertThat(context.passages())
        .extracting(ContextPassage::passageId)
        .containsExactly("A", "B", "C");
    assertThat(context.metadata())
        .containsEntry(BudgetedContextAssembler.META_DUPLICATE_IDS_REMOVED, "1");

    assertThat(retrievalRankOf(context, 0))
        .isEqualTo("0"); // A — first seen at retrieval position 0
    assertThat(retrievalRankOf(context, 1)).isEqualTo("1"); // B — retrieval position 1
    // C was at retrieval position 3, not 2 (its index in the deduplicated list).
    assertThat(retrievalRankOf(context, 2)).isEqualTo("3");
  }

  @Test
  void aLeadingDuplicateDoesNotShiftTheRetrievalRankOfLaterPassages() {
    // retrieval order: A@0, A@1 (duplicate id, dropped), B@2
    Context context =
        new BudgetedContextAssembler()
            .assemble(
                query,
                retrievalOf(
                    retrieved("A", "a", 0.9),
                    retrieved("A", "a again", 0.5),
                    retrieved("B", "b", 0.4)));

    assertThat(context.passages()).extracting(ContextPassage::passageId).containsExactly("A", "B");
    assertThat(retrievalRankOf(context, 0)).isEqualTo("0");
    assertThat(retrievalRankOf(context, 1)).isEqualTo("2"); // B — retrieval position 2, not 1
  }

  @Test
  void retrievalRankIsUnaffectedByAnEarlierBudgetSkip() {
    BudgetedContextAssembler assembler =
        new BudgetedContextAssembler(ContextBudget.ofCharacters(20));

    Context context =
        assembler.assemble(
            query,
            retrievalOf(
                retrieved("p1", textOf(15), 0.9), // fits: used 15
                retrieved("p2", textOf(15), 0.8), // 15+15 > 20 -> skipped for budget
                retrieved("p3", textOf(4), 0.7))); // 15+4 = 19 <= 20 -> fits

    assertThat(context.passages())
        .extracting(ContextPassage::passageId)
        .containsExactly("p1", "p3");
    assertThat(retrievalRankOf(context, 0)).isEqualTo("0");
    assertThat(retrievalRankOf(context, 1)).isEqualTo("2"); // p3 kept its retrieval position 2
  }

  private static String retrievalRankOf(Context context, int contextIndex) {
    return context
        .passages()
        .get(contextIndex)
        .metadata()
        .get(BudgetedContextAssembler.PASSAGE_META_RETRIEVAL_RANK);
  }

  @Test
  void theCharacterBudgetIsRespectedAndSelectionIsFirstFitInOrder() {
    BudgetedContextAssembler assembler =
        new BudgetedContextAssembler(ContextBudget.ofCharacters(100));

    Context context =
        assembler.assemble(
            query,
            retrievalOf(
                retrieved("p1", textOf(60), 0.9), // fits: used 60
                retrieved("p2", textOf(60), 0.8), // 60+60 > 100 -> skipped for budget
                retrieved("p3", textOf(30), 0.7))); // 60+30 = 90 <= 100 -> fits

    assertThat(context.passages())
        .extracting(ContextPassage::passageId)
        .containsExactly("p1", "p3");
    int usedCharacters =
        context.passages().stream().mapToInt(passage -> passage.text().length()).sum();
    assertThat(usedCharacters).isEqualTo(90).isLessThanOrEqualTo(100);
    assertThat(context.metadata())
        .containsEntry(BudgetedContextAssembler.META_USED_CHARACTERS, "90")
        .containsEntry(BudgetedContextAssembler.META_SELECTED_PASSAGES, "2")
        .containsEntry(BudgetedContextAssembler.META_SKIPPED_FOR_BUDGET, "1")
        .containsEntry(BudgetedContextAssembler.META_BUDGET_MAX_CHARACTERS, "100")
        .containsEntry(BudgetedContextAssembler.META_BUDGET_UNIT, "characters");
  }

  @Test
  void selectedPassageTextIsNeverTrimmedOrModified() {
    String exactText =
        "  leading and trailing spaces and \n newlines kept verbatim  ".strip() + " .";
    RetrievedPassage passage = retrieved("p1", exactText, 0.5);

    ContextPassage assembled =
        new BudgetedContextAssembler(ContextBudget.ofCharacters(10_000))
            .assemble(query, retrievalOf(passage))
            .passages()
            .get(0);

    assertThat(assembled.text()).isEqualTo(exactText);
  }

  @Test
  void aPassageLargerThanTheWholeBudgetIsSkippedNotTruncatedAndLaterPassagesStillFit() {
    BudgetedContextAssembler assembler =
        new BudgetedContextAssembler(ContextBudget.ofCharacters(50));

    Context context =
        assembler.assemble(
            query,
            retrievalOf(
                retrieved("oversized", textOf(120), 0.99), retrieved("small", textOf(20), 0.10)));

    assertThat(context.passages()).extracting(ContextPassage::passageId).containsExactly("small");
    assertThat(context.metadata())
        .containsEntry(BudgetedContextAssembler.META_SKIPPED_OVERSIZED, "1")
        .containsEntry(BudgetedContextAssembler.META_SKIPPED_FOR_BUDGET, "0");
  }

  @Test
  void whenEveryPassageIsOversizedTheContextIsEmptyAndSaysWhy() {
    Context context =
        new BudgetedContextAssembler(ContextBudget.ofCharacters(10))
            .assemble(
                query,
                retrievalOf(retrieved("a", textOf(50), 0.9), retrieved("b", textOf(40), 0.8)));

    assertThat(context.isEmpty()).isTrue();
    assertThat(context.metadata())
        .containsEntry(BudgetedContextAssembler.META_SKIPPED_OVERSIZED, "2")
        .containsEntry(BudgetedContextAssembler.META_SELECTED_PASSAGES, "0");
  }

  @Test
  void anInvalidBudgetIsRejected() {
    assertThatThrownBy(() -> ContextBudget.ofCharacters(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BudgetedContextAssembler(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aNullRetrievalResultOrQueryIsRejected() {
    BudgetedContextAssembler assembler = new BudgetedContextAssembler();
    assertThatThrownBy(() -> assembler.assemble(query, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> assembler.assemble(null, retrievalOf()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void assemblyIsDeterministicForTheSameRetrievalResult() {
    RetrievalResult retrieval =
        retrievalOf(
            retrieved("p1", textOf(40), 0.9),
            retrieved("p2", textOf(4000), 0.8),
            retrieved("p1", textOf(40), 0.7),
            retrieved("p3", textOf(30), 0.6));
    BudgetedContextAssembler assembler =
        new BudgetedContextAssembler(ContextBudget.ofCharacters(80));

    Context first = assembler.assemble(query, retrieval);
    Context second = assembler.assemble(query, retrieval);

    assertThat(second).isEqualTo(first);
  }

  @Test
  void theSelectedAndSkippedCountsAddUpToTheDeduplicatedInput() {
    Context context =
        new BudgetedContextAssembler(ContextBudget.ofCharacters(100))
            .assemble(
                query,
                retrievalOf(
                    retrieved("p1", textOf(60), 0.9),
                    retrieved("p2", textOf(500), 0.8), // oversized
                    retrieved("p1", textOf(60), 0.7), // duplicate id
                    retrieved("p3", textOf(60), 0.6))); // would exceed remaining budget

    Map<String, String> metadata = context.metadata();
    int retrievedPassages =
        Integer.parseInt(metadata.get(BudgetedContextAssembler.META_RETRIEVED_PASSAGES));
    int selected = Integer.parseInt(metadata.get(BudgetedContextAssembler.META_SELECTED_PASSAGES));
    int duplicates =
        Integer.parseInt(metadata.get(BudgetedContextAssembler.META_DUPLICATE_IDS_REMOVED));
    int skippedForBudget =
        Integer.parseInt(metadata.get(BudgetedContextAssembler.META_SKIPPED_FOR_BUDGET));
    int skippedOversized =
        Integer.parseInt(metadata.get(BudgetedContextAssembler.META_SKIPPED_OVERSIZED));

    assertThat(retrievedPassages).isEqualTo(4);
    assertThat(selected + skippedForBudget + skippedOversized)
        .isEqualTo(retrievedPassages - duplicates);
  }

  @Test
  void theEstimatedTokenCountIsExposedAsARoughCharacterBasedNumber() {
    Context context =
        new BudgetedContextAssembler()
            .assemble(query, retrievalOf(retrieved("p1", textOf(400), 0.9)));

    // rough: ceil(400 / 4) = 100 — a convenience estimate, not a tokenizer count.
    assertThat(context.metadata())
        .containsEntry(BudgetedContextAssembler.META_ESTIMATED_TOKENS, "100");
  }

  @Test
  void theContextExposesOnlyGenericStructures() {
    BudgetedContextAssembler assembler = new BudgetedContextAssembler();
    Context context = assembler.assemble(query, retrievalOf(retrieved("p1", "text", 0.5)));

    for (RecordComponent component : ContextPassage.class.getRecordComponents()) {
      assertThat(component.getType().getName())
          .as("ContextPassage.%s type", component.getName())
          .matches("java\\..*|org\\.signalengine\\.rag\\..*");
    }
    for (RecordComponent component : Context.class.getRecordComponents()) {
      assertThat(component.getType().getName())
          .as("Context.%s type", component.getName())
          .matches("java\\..*");
    }
    assertThat(context.metadata().values()).allSatisfy(value -> assertThat(value).isNotNull());
    assertThat(assembler.descriptor().type()).isEqualTo(RagComponentType.CONTEXT_ASSEMBLER);
  }
}
