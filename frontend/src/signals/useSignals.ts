import { useCallback, useEffect, useState } from 'react';

import { api, type SignalResponse, type SignalState } from '../api';

/** A signal that has a persisted identity — every signal the backend actually returns. */
export type Signal = SignalResponse & {
  id: string;
  relevantInformationId: string;
  state: SignalState;
  createdAt: string;
};

type SignalsState =
  | { status: 'loading' }
  | { status: 'error'; error: unknown }
  | { status: 'ready'; signals: Signal[] };

/**
 * Fetches the most recent signals, newest first — exactly the order `GET /api/v1/signals` returns
 * (docs/02-functional-spec.md Section 9.4, workflow W6). No frontend sorting, filtering, or ranking.
 */
export function useSignals() {
  const [state, setState] = useState<SignalsState>({ status: 'loading' });

  const refresh = useCallback(async () => {
    setState({ status: 'loading' });
    try {
      const signals = await api.listSignals();
      setState({ status: 'ready', signals: signals.filter(isComplete) });
    } catch (error) {
      setState({ status: 'error', error });
    }
  }, []);

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
