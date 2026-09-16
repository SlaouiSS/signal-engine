/**
 * The record of one RAG pipeline run (docs/07-rag.md Section 21).
 *
 * <p>{@link org.signalengine.rag.execution.RagExecution} bundles everything one run produced
 * &mdash; the original and effective query, the retrieval result, the assembled context, the answer
 * (when generation ran), and a per-stage list of which component played each role with its {@link
 * org.signalengine.rag.ComponentDescriptor}. It is an immutable value: an {@link
 * org.signalengine.rag.evaluation.Evaluator} consumes one without any ability to re-run or mutate
 * the pipeline, and a later improvement step can compare two executions to see what a component
 * swap changed.
 */
package org.signalengine.rag.execution;
