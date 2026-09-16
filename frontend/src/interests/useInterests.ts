import { useCallback, useEffect, useState } from 'react';

import { api, type InterestResponse } from '../api';

/** A configured interest that has a persisted identity — every interest returned by the backend. */
export type Interest = InterestResponse & { id: string; areaOfInterestCode: string };

type InterestsState =
  | { status: 'loading' }
  | { status: 'error'; error: unknown }
  | { status: 'ready'; interests: Interest[] };

/** Fetches every configured interest, across all areas. */
export function useInterests() {
  const [state, setState] = useState<InterestsState>({ status: 'loading' });

  const refresh = useCallback(async () => {
    setState({ status: 'loading' });
    try {
      const interests = await api.listInterests();
      setState({ status: 'ready', interests: interests.filter(isComplete) });
    } catch (error) {
      setState({ status: 'error', error });
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { state, refresh };
}

function isComplete(interest: InterestResponse): interest is Interest {
  return typeof interest.id === 'string' && typeof interest.areaOfInterestCode === 'string';
}
