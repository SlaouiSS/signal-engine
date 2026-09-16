/**
 * A title-shaped first line is at most this long — real headlines observed in collected content
 * (e.g. "Hugo Boss Chairman to Step Down Amid Frasers Group Pressure") are well under this; a first
 * paragraph this long is body text, not a title.
 */
const MAX_HEADLINE_LENGTH = 160;

/** How much of the content a fallback preview shows when no title-shaped line is present. */
const PREVIEW_LENGTH = 160;

/**
 * Derives a display headline from a raw information item's already-collected, already-persisted
 * `normalizedContent` — never invented, never AI-generated. For feed-collected items, ingestion
 * already joins the entry's title and body as `"title\n\nbody"`
 * (`HttpSourceCollector.combinedText`), so the text before the first blank line is a real,
 * source-backed title whenever it is short enough to plausibly be one. Content with no such split
 * (HTML-extracted articles, plain text) falls back to a truncated preview of the content itself —
 * still verbatim source text, just shorter.
 *
 * `RawInformationItem` itself carries no title field (docs/05-data-model.md Section 8); this reads
 * one out of content structure that ingestion already produces, it does not add one.
 */
export function extractHeadline(normalizedContent: string | undefined): string | undefined {
  if (!normalizedContent) {
    return undefined;
  }
  const trimmed = normalizedContent.trim();
  if (!trimmed) {
    return undefined;
  }

  const blankLineIndex = trimmed.indexOf('\n\n');
  if (blankLineIndex > 0) {
    const firstSegment = trimmed.slice(0, blankLineIndex).trim();
    if (firstSegment.length > 0 && firstSegment.length <= MAX_HEADLINE_LENGTH) {
      return firstSegment;
    }
  }

  return truncate(trimmed.replace(/\s+/g, ' '), PREVIEW_LENGTH);
}

function truncate(text: string, maxLength: number): string {
  if (text.length <= maxLength) {
    return text;
  }
  const cut = text.slice(0, maxLength);
  const lastSpace = cut.lastIndexOf(' ');
  const safeCut = lastSpace > 0 ? cut.slice(0, lastSpace) : cut;
  return `${safeCut}…`;
}
