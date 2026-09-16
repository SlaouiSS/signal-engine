/**
 * Generation: producing a grounded answer from a {@link org.signalengine.rag.context.Context}, and
 * validating it (docs/07-rag.md Section 10, 21).
 *
 * <p>Generation is optional &mdash; a pipeline without a {@link
 * org.signalengine.rag.generation.Generator} still produces a retrieval result and a context. When
 * present, the generator is <b>provider-independent</b>: this package names no LLM, no provider, no
 * SDK. A later implementation may call the Task 6A AI capability path
 * (docs/adr/0006-ai-java-python-foundation.md); the core neither knows nor cares.
 *
 * <p>{@link org.signalengine.rag.generation.RagAnswer} carries the answer text, its {@link
 * org.signalengine.rag.provenance.Citation citations}, and an explicit "answered / insufficient
 * evidence" flag so an unsupported answer is never fabricated. A {@link
 * org.signalengine.rag.generation.Generator} that cannot obtain a valid answer at all (provider
 * down, timeout, output invalid after one repair) throws {@link
 * org.signalengine.rag.generation.GenerationException} &mdash; distinct from the
 * insufficient-evidence outcome.
 *
 * <p>{@link org.signalengine.rag.generation.AnswerValidator} is an optional, composable runtime
 * stage. The supplied {@link org.signalengine.rag.generation.GroundingAnswerValidator} (Task 8.5,
 * docs/adr/0015-rag-grounded-generator.md) does a deterministic <b>structural</b> grounding check
 * against the context: it drops citations that do not resolve to a supplied passage, collapses
 * duplicates, and downgrades an answered response with no valid support to insufficient evidence.
 * This is a runtime hallucination control; it is <b>not</b> semantic factuality/faithfulness
 * scoring, which is the separate, after-the-fact concern of {@link
 * org.signalengine.rag.evaluation}.
 */
package org.signalengine.rag.generation;
