/**
 * Retrieval: turning a {@link org.signalengine.rag.query.Query} into an ordered set of candidate
 * passages, and the optional stage that reorders them (docs/07-rag.md Section 7, 21).
 *
 * <p>{@link org.signalengine.rag.retrieval.Retriever} is intentionally generic: dense, sparse,
 * hybrid, or any future custom strategy implements the same contract, and the core never learns
 * whether the passages come from pgvector, Elasticsearch, OpenSearch, Qdrant, another store, or a
 * remote service. Each {@link org.signalengine.rag.retrieval.RetrievedPassage} carries its text,
 * its {@link org.signalengine.rag.provenance.Provenance}, a strategy-defined score, and an open
 * metadata map &mdash; enough for context assembly and citation downstream.
 *
 * <p>{@link org.signalengine.rag.retrieval.Reranker} is an optional, composable stage that takes a
 * {@link org.signalengine.rag.retrieval.RetrievalResult} and returns a reordered/pruned one;
 * cross-encoder reranking and diversity reranking are added here later without touching retrieval
 * or context assembly.
 */
package org.signalengine.rag.retrieval;
