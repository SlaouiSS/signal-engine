import { useCallback, useEffect, useState } from 'react';

import { api, type AreaOfInterestResponse } from '../api';

/** One of the six fixed areas of interest, always carrying its code and name. */
export type AreaOfInterest = AreaOfInterestResponse & { code: string; name: string };

type AreasState =
  | { status: 'loading' }
  | { status: 'error'; error: unknown }
  | { status: 'ready'; areas: AreaOfInterest[] };

/** Fetches the six fixed areas of interest (docs/01-product-spec.md Section 1.1). Read-only. */
export function useAreasOfInterest() {
  const [state, setState] = useState<AreasState>({ status: 'loading' });

  const refresh = useCallback(async () => {
    setState({ status: 'loading' });
    try {
      const areas = await api.listAreasOfInterest();
      setState({ status: 'ready', areas: areas.filter(isComplete) });
    } catch (error) {
      setState({ status: 'error', error });
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { state, refresh };
}

function isComplete(area: AreaOfInterestResponse): area is AreaOfInterest {
  return typeof area.code === 'string' && typeof area.name === 'string';
}
