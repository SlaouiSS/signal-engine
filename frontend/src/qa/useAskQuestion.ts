import { useCallback, useState } from 'react';

import { api, type CitationResponse } from '../api';

/** A citation that actually carries an identity — every citation the backend returns. */
export type Citation = CitationResponse & { passageId: string };

type QaState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'error'; error: unknown }
  | { status: 'ready'; question: string; answered: boolean; answer: string; citations: Citation[] };

/**
 * Asks one grounded question via `POST /api/v1/questions` (docs/02-functional-spec.md Section 13,
 * workflow W10). Single-question, single-answer — no conversation history, no frontend RAG: the
 * backend performs retrieval, grounding, and generation; this only submits and renders the result.
 */
export function useAskQuestion() {
  const [state, setState] = useState<QaState>({ status: 'idle' });

  const ask = useCallback(async (question: string) => {
    setState({ status: 'loading' });
    try {
      const response = await api.askQuestion({ question });
      setState({
        status: 'ready',
        question,
        answered: response.answered === true,
        answer: response.answer ?? '',
        citations: (response.citations ?? []).filter(hasPassageId),
      });
    } catch (error) {
      setState({ status: 'error', error });
    }
  }, []);

  return { state, ask };
}

function hasPassageId(citation: CitationResponse): citation is Citation {
  return typeof citation.passageId === 'string';
}
