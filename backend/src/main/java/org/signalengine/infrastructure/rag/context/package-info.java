/**
 * Infrastructure composition for RAG context assembly (docs/07-rag.md Section 25;
 * docs/adr/0014-rag-context-assembly.md).
 *
 * <p>The assembler itself &mdash; {@link org.signalengine.rag.context.BudgetedContextAssembler}
 * &mdash; is framework-free and lives in the RAG core. This package only holds the Spring wiring:
 * {@link org.signalengine.infrastructure.rag.context.RagContextConfiguration} binds the {@link
 * org.signalengine.rag.context.ContextBudget} from {@link
 * org.signalengine.infrastructure.rag.context.RagContextProperties} and composes the minimal
 * query-time {@link org.signalengine.rag.pipeline.RagPipeline} (retriever then assembler, no
 * generator).
 *
 * <p>No PostgreSQL, HTTP, provider or Signal Engine business type is involved &mdash; context
 * assembly consumes a {@link org.signalengine.rag.retrieval.RetrievalResult} and produces a {@link
 * org.signalengine.rag.context.Context}, both generic.
 */
package org.signalengine.infrastructure.rag.context;
