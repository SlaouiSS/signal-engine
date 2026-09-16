package org.signalengine.rag;

/**
 * The kind of replaceable capability a RAG component provides. Used by {@link ComponentDescriptor}
 * so a finished {@link org.signalengine.rag.execution.RagExecution} records which stage each
 * component played, without the core needing a configuration framework.
 */
public enum RagComponentType {
  CHUNKER,
  EMBEDDING_MODEL,
  QUERY_PROCESSOR,
  RETRIEVER,
  RERANKER,
  CONTEXT_ASSEMBLER,
  CONTEXT_REFINER,
  GENERATOR,
  ANSWER_VALIDATOR,
  EVALUATOR,
  INDEXING_PIPELINE,
  INDEXED_PASSAGE_STORE
}
