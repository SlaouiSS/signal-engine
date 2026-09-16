/**
 * Context: the first-class, generation-independent output of the RAG pipeline (docs/07-rag.md
 * Section 21).
 *
 * <p>A {@link org.signalengine.rag.context.Context} is the selected, ordered set of {@link
 * org.signalengine.rag.context.ContextPassage passages} chosen to answer a query, each still
 * carrying its {@link org.signalengine.rag.provenance.Provenance}. It is <b>not</b> a formatted LLM
 * prompt &mdash; a {@link org.signalengine.rag.generation.Generator} is free to render it however
 * it needs. A caller can request {@code Query -> Retrieval -> Context} and stop there (semantic
 * search, context inspection, retrieval evaluation) without any generator being involved.
 *
 * <p>{@link org.signalengine.rag.context.ContextAssembler} builds the context from a {@link
 * org.signalengine.rag.retrieval.RetrievalResult}. The supplied {@link
 * org.signalengine.rag.context.BudgetedContextAssembler} (Task 8.4,
 * docs/adr/0014-rag-context-assembly.md) selects whole passages in retrieval order until a
 * model-independent {@link org.signalengine.rag.context.ContextBudget character budget} is reached;
 * it removes exact-duplicate passage ids, never reorders, and never modifies passage text.
 *
 * <p>{@link org.signalengine.rag.context.ContextRefiner} is an optional, composable stage over an
 * already-assembled context &mdash; the seam where context compression, near-redundancy removal and
 * diversification are added later without changing the assembler. It is deliberately left
 * unimplemented for now (Task 8.4).
 */
package org.signalengine.rag.context;
