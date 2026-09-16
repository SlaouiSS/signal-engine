/**
 * Reusable, provider-independent RAG core &mdash; contracts and composition only (docs/07-rag.md
 * Section 21; docs/adr/0009-rag-core-architecture-and-contracts.md).
 *
 * <p>This package tree is a self-contained retrieval-augmented-generation library. It is
 * deliberately kept free of every Signal Engine concern so a future project can depend on it
 * unchanged:
 *
 * <ul>
 *   <li><b>No Signal Engine business concept.</b> Nothing here references {@code Signal}, {@code
 *       RelevantInformation}, {@code RawInformationItem}, {@code AreaOfInterest}, {@code Interest},
 *       {@code Source}, a Signal Engine repository, or a Signal Engine business rule. Retrieval
 *       works over generic {@link org.signalengine.rag.retrieval.RetrievedPassage passages}
 *       carrying generic {@link org.signalengine.rag.provenance.Provenance provenance}.
 *   <li><b>No framework.</b> No Spring annotation, no Spring type, no {@code jakarta.persistence} /
 *       Hibernate type, no HTTP client, no SQL, no vector-store type, no LLM provider SDK, no model
 *       name. Spring wires concrete implementations of these contracts in the infrastructure layer
 *       in later tasks (docs/04-architecture.md Section 4.1).
 *   <li><b>No infrastructure knowledge.</b> The core does not know whether retrieval uses pgvector,
 *       Elasticsearch, OpenSearch, Qdrant, another store, or an external service, nor which LLM
 *       provider a {@link org.signalengine.rag.generation.Generator} calls.
 * </ul>
 *
 * <h2>Runtime pipeline</h2>
 *
 * <pre>
 *   Query
 *     &rarr; Query processing   [optional]  {@link org.signalengine.rag.query.QueryProcessor}
 *     &rarr; Retrieval                      {@link org.signalengine.rag.retrieval.Retriever}
 *     &rarr; Reranking          [optional]  {@link org.signalengine.rag.retrieval.Reranker}
 *     &rarr; Context assembly               {@link org.signalengine.rag.context.ContextAssembler}
 *     &rarr; Context refinement [optional]  {@link org.signalengine.rag.context.ContextRefiner}
 *     &rarr; Generation         [optional]  {@link org.signalengine.rag.generation.Generator}
 *     &rarr; Answer validation  [optional]  {@link org.signalengine.rag.generation.AnswerValidator}
 * </pre>
 *
 * <p>Only {@code Retriever} and {@code ContextAssembler} are required. Each optional stage can be
 * added or removed without rewriting the others &mdash; the pipeline is composed from the stage
 * contracts by {@link org.signalengine.rag.pipeline.StagedRagPipeline}, never by subclassing and
 * never by one large service that contains every behaviour.
 *
 * <h2>Independently exposable outputs</h2>
 *
 * <p>{@link org.signalengine.rag.execution.RagExecution} carries, each usable on its own: the
 * {@link org.signalengine.rag.retrieval.RetrievalResult retrieval result}, the assembled {@link
 * org.signalengine.rag.context.Context context}, the generated {@link
 * org.signalengine.rag.generation.RagAnswer answer} (when generation ran), the {@link
 * org.signalengine.rag.provenance.Citation citations}, and enough structure for a later {@link
 * org.signalengine.rag.evaluation.Evaluator} to consume the run.
 *
 * <h2>Evaluation and indexing are separate concerns</h2>
 *
 * <p>{@link org.signalengine.rag.evaluation} sits <i>outside</i> the runtime pipeline: an evaluator
 * consumes a finished {@code RagExecution} and never mutates or drives the pipeline. {@link
 * org.signalengine.rag.indexing} is the future-facing, not-yet-implemented boundary for turning
 * content into retrievable passages; the query-time pipeline never depends on it.
 */
package org.signalengine.rag;
