import { useCallback, useEffect, useState } from 'react';

import { api, type SourceResponse } from '../api';

/** A configured source that has a persisted identity — every source returned by the backend. */
export type Source = SourceResponse & { id: string };

type SourcesState =
  | { status: 'loading' }
  | { status: 'error'; error: unknown }
  | { status: 'ready'; sources: Source[] };

/** Fetches the configured sources and exposes a way to refresh them after a mutation. */
export function useSources() {
  const [state, setState] = useState<SourcesState>({ status: 'loading' });

  const refresh = useCallback(async () => {
    setState({ status: 'loading' });
    try {
      const sources = await api.listSources();
      setState({ status: 'ready', sources: sources.filter(hasId) });
    } catch (error) {
      setState({ status: 'error', error });
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { state, refresh };
}

function hasId(source: SourceResponse): source is Source {
  return typeof source.id === 'string';
}
