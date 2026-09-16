import { useCallback, useEffect, useState } from 'react';

import { api, type RelevantInformationResponse } from '../api';

/** A relevant-information record that has a persisted identity — every record the backend returns. */
export type RelevantInformationItem = RelevantInformationResponse & {
  id: string;
  createdAt: string;
};

type RecentRelevantInformationState =
  | { status: 'loading' }
  | { status: 'error'; error: unknown }
  | { status: 'ready'; items: RelevantInformationItem[] };

/**
 * Fetches the most recent relevant-information records, newest first — exactly the order
 * `GET /api/v1/relevant-information` returns. This is every retained record across every
 * configured area, not only the subset that became a Signal (unlike the Signals page, which is
 * `GET /api/v1/signals`). No frontend sorting, filtering, or area balancing.
 */
export function useRecentRelevantInformation() {
  const [state, setState] = useState<RecentRelevantInformationState>({ status: 'loading' });

  const refresh = useCallback(async () => {
    setState({ status: 'loading' });
    try {
      const items = await api.listRelevantInformation();
      setState({ status: 'ready', items: items.filter(isComplete) });
    } catch (error) {
      setState({ status: 'error', error });
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { state, refresh };
}

function isComplete(item: RelevantInformationResponse): item is RelevantInformationItem {
  return typeof item.id === 'string' && typeof item.createdAt === 'string';
}
