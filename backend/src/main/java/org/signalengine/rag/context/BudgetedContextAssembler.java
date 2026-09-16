package org.signalengine.rag.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.signalengine.rag.retrieval.RetrievedPassage;

/**
 * The supplied {@link ContextAssembler}: turn a {@link RetrievalResult} into a bounded {@link
 * Context} by <b>selecting whole passages in retrieval order until a character budget is
 * reached</b> (docs/07-rag.md Section 25; docs/adr/0014-rag-context-assembly.md).
 *
 * <p>What it does, in order:
 *
 * <ol>
 *   <li><b>Exact-duplicate removal</b> &mdash; if the same {@code passageId} appears more than
 *       once, the first occurrence is kept and the rest dropped. No semantic or near-duplicate
 *       detection (that is Task 6B's concern and stays out of the generic core).
 *   <li><b>Ordering</b> &mdash; retrieval order is preserved exactly. The assembler introduces no
 *       ranking of its own (not by score, source, recency, or any business attribute); it relies on
 *       the deterministic order the {@link org.signalengine.rag.retrieval.Retriever} already
 *       guarantees.
 *   <li><b>Budgeting</b> &mdash; passages are added in order while the running character total
 *       stays within {@link ContextBudget#maxCharacters()}. A passage that would not fit is skipped
 *       and the next one is still considered (first-fit), so as many whole passages as possible are
 *       kept. A passage whose own text exceeds the entire budget is never included &mdash; it is
 *       reported, not truncated.
 * </ol>
 *
 * <p>It never modifies passage text: no trimming, summarising, compressing, merging or rewriting.
 * If the context would be too large, fewer complete passages are selected. Compression is a later
 * {@link ContextRefiner} concern and is deliberately not done here.
 *
 * <p>Provenance and the passage identifier are carried through unchanged. Each selected passage's
 * retrieval score and its 0-based position in the received retrieval result &mdash; the position
 * before duplicate removal, so it stays stable when an earlier duplicate is dropped &mdash; are
 * copied into the context passage metadata ({@code retrievalScore}, {@code retrievalRank}) so the
 * context stays traceable back to retrieval without promoting a post-assembly ranking signal to a
 * field.
 *
 * <p>Deterministic: the same {@link RetrievalResult} always yields the same {@link Context}.
 */
public final class BudgetedContextAssembler implements ContextAssembler {

  /** {@link ComponentDescriptor#implementationId()} this assembler reports. */
  public static final String IMPLEMENTATION_ID = "budgeted-context-assembler";

  /** The fixed ordering policy: passages appear exactly as retrieval returned them. */
  public static final String ORDERING_POLICY = "retrieval-order";

  // --- Context.metadata() keys ---------------------------------------------------------------

  public static final String META_ASSEMBLER = "assembler";
  public static final String META_ORDERING_POLICY = "orderingPolicy";
  public static final String META_BUDGET_UNIT = "budgetUnit";
  public static final String META_BUDGET_MAX_CHARACTERS = "budgetMaxCharacters";
  public static final String META_USED_CHARACTERS = "usedCharacters";
  public static final String META_ESTIMATED_TOKENS = "estimatedTokens";
  public static final String META_RETRIEVED_PASSAGES = "retrievedPassages";
  public static final String META_SELECTED_PASSAGES = "selectedPassages";
  public static final String META_DUPLICATE_IDS_REMOVED = "duplicatePassageIdsRemoved";
  public static final String META_SKIPPED_FOR_BUDGET = "skippedForBudget";
  public static final String META_SKIPPED_OVERSIZED = "skippedOversized";

  // --- ContextPassage.metadata() keys added by assembly ------------------------------------

  public static final String PASSAGE_META_RETRIEVAL_RANK = "retrievalRank";
  public static final String PASSAGE_META_RETRIEVAL_SCORE = "retrievalScore";

  /**
   * Divisor for the rough character-based token estimate exposed in metadata. Not a tokenizer:
   * roughly four characters per token for English prose. The budget itself is enforced in
   * characters, never in this estimate.
   */
  private static final int ESTIMATED_CHARACTERS_PER_TOKEN = 4;

  private final ContextBudget budget;

  /** Assembles with the provisional {@link ContextBudget#ofDefault() default budget}. */
  public BudgetedContextAssembler() {
    this(ContextBudget.ofDefault());
  }

  public BudgetedContextAssembler(ContextBudget budget) {
    this.budget = Objects.requireNonNull(budget, "budget must not be null");
  }

  @Override
  public Context assemble(Query query, RetrievalResult retrieved) {
    Objects.requireNonNull(query, "query must not be null");
    Objects.requireNonNull(retrieved, "retrieved must not be null");

    // Keep the first occurrence of each passage id, and remember its 0-based position in the
    // retrieval result the assembler received. That original position is what `retrievalRank`
    // means (docs/07-rag.md Section 25.2; docs/adr/0014) — it must not shift when an earlier
    // duplicate is dropped, so it cannot be the index in the deduplicated list.
    List<RankedPassage> deduplicated = new ArrayList<>();
    Set<String> seenPassageIds = new LinkedHashSet<>();
    int duplicateIdsRemoved = 0;
    int retrievalPosition = 0;
    for (RetrievedPassage passage : retrieved.passages()) {
      if (seenPassageIds.add(passage.passageId())) {
        deduplicated.add(new RankedPassage(passage, retrievalPosition));
      } else {
        duplicateIdsRemoved++;
      }
      retrievalPosition++;
    }

    List<ContextPassage> selected = new ArrayList<>();
    int usedCharacters = 0;
    int skippedForBudget = 0;
    int skippedOversized = 0;
    for (RankedPassage ranked : deduplicated) {
      RetrievedPassage passage = ranked.passage();
      int passageCharacters = passage.text().length();
      if (passageCharacters > budget.maxCharacters()) {
        skippedOversized++;
      } else if (usedCharacters + passageCharacters > budget.maxCharacters()) {
        skippedForBudget++;
      } else {
        selected.add(toContextPassage(passage, ranked.retrievalRank()));
        usedCharacters += passageCharacters;
      }
    }

    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put(META_ASSEMBLER, IMPLEMENTATION_ID);
    metadata.put(META_ORDERING_POLICY, ORDERING_POLICY);
    metadata.put(META_BUDGET_UNIT, "characters");
    metadata.put(META_BUDGET_MAX_CHARACTERS, Integer.toString(budget.maxCharacters()));
    metadata.put(META_USED_CHARACTERS, Integer.toString(usedCharacters));
    metadata.put(META_ESTIMATED_TOKENS, Integer.toString(estimateTokens(usedCharacters)));
    metadata.put(META_RETRIEVED_PASSAGES, Integer.toString(retrieved.passages().size()));
    metadata.put(META_SELECTED_PASSAGES, Integer.toString(selected.size()));
    metadata.put(META_DUPLICATE_IDS_REMOVED, Integer.toString(duplicateIdsRemoved));
    metadata.put(META_SKIPPED_FOR_BUDGET, Integer.toString(skippedForBudget));
    metadata.put(META_SKIPPED_OVERSIZED, Integer.toString(skippedOversized));

    return new Context(selected, metadata);
  }

  @Override
  public ComponentDescriptor descriptor() {
    return new ComponentDescriptor(
        RagComponentType.CONTEXT_ASSEMBLER,
        IMPLEMENTATION_ID,
        ORDERING_POLICY + "/v1;maxChars=" + budget.maxCharacters());
  }

  /** The character budget this assembler enforces. */
  public ContextBudget budget() {
    return budget;
  }

  /** A retrieved passage paired with its 0-based position in the received retrieval result. */
  private record RankedPassage(RetrievedPassage passage, int retrievalRank) {}

  private static ContextPassage toContextPassage(RetrievedPassage passage, int retrievalRank) {
    Map<String, String> passageMetadata = new LinkedHashMap<>(passage.metadata());
    passageMetadata.put(PASSAGE_META_RETRIEVAL_RANK, Integer.toString(retrievalRank));
    passageMetadata.put(PASSAGE_META_RETRIEVAL_SCORE, Double.toString(passage.score()));
    return new ContextPassage(
        passage.passageId(), passage.text(), passage.provenance(), passageMetadata);
  }

  private static int estimateTokens(int characters) {
    return characters == 0
        ? 0
        : (characters + ESTIMATED_CHARACTERS_PER_TOKEN - 1) / ESTIMATED_CHARACTERS_PER_TOKEN;
  }
}
