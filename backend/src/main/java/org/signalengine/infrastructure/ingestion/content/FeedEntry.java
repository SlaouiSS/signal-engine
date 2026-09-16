package org.signalengine.infrastructure.ingestion.content;

import java.time.Instant;

/**
 * One entry parsed out of an RSS {@code <item>} or an Atom {@code <entry>} — already reduced to
 * plain text (any embedded HTML in the description/content has been extracted), never the raw feed
 * markup (docs/08-ingestion.md Section 13 — provenance must be the entry's own reference, not the
 * feed's).
 *
 * <p>Every field except {@code text} may be {@code null}: a source-provided identity ({@code guid}/
 * {@code id}), a title, an entry-specific link, and a publication time are all optional per
 * RSS/Atom.
 */
public record FeedEntry(String id, String link, String title, String text, Instant publishedAt) {}
