import type { Source } from '../sources/useSources';
import ProvenanceDetails from '../ui/ProvenanceDetails';
import type { SearchResult } from './useSearch';

/** One source-grounded search result: the retrieved passage, its score, and its provenance. */
export default function SearchResultItem({
  result,
  sources,
}: {
  result: SearchResult;
  sources: Source[];
}) {
  return (
    <li>
      <p>{result.text}</p>
      <p className="text-muted">Score: {result.score.toFixed(3)}</p>
      <ProvenanceDetails provenance={result.provenance} sources={sources} />
    </li>
  );
}
