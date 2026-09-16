import type { ProvenanceResponse } from '../api';
import { resolveSourceLabel } from '../sources/resolveSourceLabel';
import type { Source } from '../sources/useSources';
import ExternalLink from './ExternalLink';

/**
 * Renders one passage's provenance — resolved source name, title, document id, and a link to the
 * original source — identically wherever provenance is shown (Search results, Q&A citations).
 * Renders nothing when there is no provenance at all.
 */
export default function ProvenanceDetails({
  provenance,
  sources,
}: {
  provenance: ProvenanceResponse | undefined;
  sources: Source[];
}) {
  if (!provenance) {
    return null;
  }
  return (
    <p className="text-secondary">
      <span>Source: {resolveSourceLabel(provenance.sourceId, sources)}</span>
      {provenance.title && <span> — {provenance.title}</span>}
      {provenance.documentId && <span> (document {provenance.documentId})</span>}{' '}
      <ExternalLink href={provenance.originUri}>Open original source</ExternalLink>
    </p>
  );
}
