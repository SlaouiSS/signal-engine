/**
 * Deterministic parsing and content extraction for the {@code http} source type
 * (docs/08-ingestion.md Section 6 "Parsing and Content Extraction"; docs/03-technical-spec.md
 * Section 10.1). Turns a raw HTTP response (an RSS/Atom feed document, or an HTML page) into one or
 * more {@link org.signalengine.application.ingestion.CollectedItem}s carrying meaningful text —
 * never the whole feed document or the whole page — before normalization and AI processing.
 *
 * <p>Everything here is deterministic: no AI, no LLM, no network I/O of its own. It is used only by
 * {@link org.signalengine.infrastructure.ingestion.HttpSourceCollector}, which remains the one
 * {@code SourceCollector} adapter and the one place source-specific parsing/extraction lives — this
 * package does not introduce a new port, since it has exactly one caller and no planned second
 * implementation (CLAUDE.md Section 9).
 */
package org.signalengine.infrastructure.ingestion.content;
