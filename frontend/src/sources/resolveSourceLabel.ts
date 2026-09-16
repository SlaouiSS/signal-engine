import type { Source } from './useSources';

/**
 * Resolves a provenance `sourceId` to its configured source name, falling back to the raw id when
 * the source isn't (yet) loaded or known. Shared by every screen that renders provenance (Signals,
 * Search, Q&A) so the same fallback behavior applies everywhere.
 */
export function resolveSourceLabel(sourceId: string | undefined, sources: Source[]): string {
  if (!sourceId) {
    return 'Unknown source';
  }
  const source = sources.find((candidate) => candidate.id === sourceId);
  return source?.name ?? sourceId;
}
