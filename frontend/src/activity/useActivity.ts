import { useCallback, useEffect, useState } from 'react';

import { api, type ActivityRecordResponse } from '../api';

/** An activity record that has a persisted identity and timestamp — every record the backend returns. */
export type ActivityRecord = ActivityRecordResponse & { id: string; occurredAt: string };

type ActivityState =
  | { status: 'loading' }
  | { status: 'error'; error: unknown }
  | { status: 'ready'; records: ActivityRecord[] };

/**
 * Fetches the most recent system activity, newest first — exactly the order `GET /api/v1/activity`
 * returns (docs/02-functional-spec.md Section 15.1, workflow W11). No frontend sorting, filtering,
 * or pagination.
 */
export function useActivity() {
  const [state, setState] = useState<ActivityState>({ status: 'loading' });

  const refresh = useCallback(async () => {
    setState({ status: 'loading' });
    try {
      const records = await api.listActivity();
      setState({ status: 'ready', records: records.filter(isComplete) });
    } catch (error) {
      setState({ status: 'error', error });
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { state, refresh };
}

function isComplete(record: ActivityRecordResponse): record is ActivityRecord {
  return typeof record.id === 'string' && typeof record.occurredAt === 'string';
}
