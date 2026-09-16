import { useCallback, useEffect, useState } from 'react';

import { api, ApiRequestError, type SignalResponse } from '../api';
import type { Signal } from './useSignals';

type SignalForRelevantInformationState =
  | { status: 'loading' }
  | { status: 'error'; error: unknown }
  | { status: 'ready'; signal: Signal | null };

/**
 * Looks up the Signal derived from one relevant-information record, if any
 * (`GET /api/v1/relevant-information/{id}/signal`). Most relevant information never becomes a
 * Signal (docs/05-data-model.md Section 9-10), so a 404 here is a normal outcome — surfaced as
 * `{status: 'ready', signal: null}`, not as an error.
 */
export function useSignalForRelevantInformation(relevantInformationId: string) {
  const [state, setState] = useState<SignalForRelevantInformationState>({ status: 'loading' });

  const refresh = useCallback(async () => {
    setState({ status: 'loading' });
    try {
      const signal = await api.getSignalForRelevantInformation(relevantInformationId);
      setState({ status: 'ready', signal: isComplete(signal) ? signal : null });
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 404) {
        setState({ status: 'ready', signal: null });
        return;
      }
      setState({ status: 'error', error });
    }
  }, [relevantInformationId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { state, refresh };
}

function isComplete(signal: SignalResponse): signal is Signal {
  return (
    typeof signal.id === 'string' &&
    typeof signal.relevantInformationId === 'string' &&
    typeof signal.state === 'string' &&
    typeof signal.createdAt === 'string'
  );
}
