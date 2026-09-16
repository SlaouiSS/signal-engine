import { useCallback, useEffect, useState } from 'react';

import { api, type RawInformationItemResponse, type RelevantInformationResponse } from '../api';

type DetailState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'error'; error: unknown }
  | {
      status: 'ready';
      relevantInformation: RelevantInformationResponse;
      rawInformationItems: RawInformationItemResponse[];
    };

/**
 * Fetches the Relevant Information drill-down for one signal: the record itself
 * (`GET /api/v1/relevant-information/{id}`) and its contributing raw items
 * (`GET /api/v1/relevant-information/{id}/raw-information-items`), which is where provenance
 * (source, original link) actually lives (docs/05-data-model.md Section 9, 15).
 *
 * `relevantInformationId` of `null` means "no signal selected" — nothing is fetched.
 */
export function useRelevantInformationDetail(relevantInformationId: string | null) {
  const [state, setState] = useState<DetailState>({ status: 'idle' });

  const refresh = useCallback(async () => {
    if (relevantInformationId === null) {
      setState({ status: 'idle' });
      return;
    }
    setState({ status: 'loading' });
    try {
      const [relevantInformation, rawInformationItems] = await Promise.all([
        api.getRelevantInformation(relevantInformationId),
        api.listContributingRawInformationItems(relevantInformationId),
      ]);
      setState({ status: 'ready', relevantInformation, rawInformationItems });
    } catch (error) {
      setState({ status: 'error', error });
    }
  }, [relevantInformationId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { state, refresh };
}
