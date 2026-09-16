import { useCallback, useState } from 'react';

import { api, type SearchResultResponse } from '../api';

/** A search result that actually carries a passage to show — id, text, and score. */
export type SearchResult = SearchResultResponse & {
  passageId: string;
  text: string;
  score: number;
};

type SearchState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'error'; error: unknown }
  | { status: 'ready'; results: SearchResult[] };

/**
 * Runs a semantic search via `POST /api/v1/search` (docs/02-functional-spec.md Section 12.3,
 * workflow W9). No client-side ranking, sorting, or filtering — results are kept in exactly the
 * order the backend returns them.
 */
export function useSearch() {
  const [state, setState] = useState<SearchState>({ status: 'idle' });

  const search = useCallback(async (query: string) => {
    setState({ status: 'loading' });
    try {
      const response = await api.search({ query });
      setState({ status: 'ready', results: (response.results ?? []).filter(hasContent) });
    } catch (error) {
      setState({ status: 'error', error });
    }
  }, []);

  return { state, search };
}

function hasContent(result: SearchResultResponse): result is SearchResult {
  return (
    typeof result.passageId === 'string' &&
    typeof result.text === 'string' &&
    typeof result.score === 'number'
  );
}
