import type { Source } from '../sources/useSources';
import ProvenanceDetails from '../ui/ProvenanceDetails';
import type { Citation } from './useAskQuestion';

/** One citation backing a grounded answer: the quoted span (if any) and its provenance. */
export default function CitationItem({
  citation,
  sources,
}: {
  citation: Citation;
  sources: Source[];
}) {
  return (
    <li>
      {citation.quotedText && <p>“{citation.quotedText}”</p>}
      <ProvenanceDetails provenance={citation.provenance} sources={sources} />
    </li>
  );
}
