/**
 * The query entering the RAG pipeline and the optional stage that transforms it (docs/07-rag.md
 * Section 21).
 *
 * <p>{@link org.signalengine.rag.query.Query} is a plain information need plus generic metadata
 * filters and hints. {@link org.signalengine.rag.query.QueryProcessor} is an optional, composable
 * stage: query rewriting, expansion, translation and filter inference are all added later as {@code
 * QueryProcessor}s without changing retrieval or any other stage.
 */
package org.signalengine.rag.query;
