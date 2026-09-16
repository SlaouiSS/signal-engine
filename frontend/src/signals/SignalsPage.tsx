import { useState } from 'react';

import { useAreasOfInterest } from '../interests/useAreasOfInterest';
import { useSources } from '../sources/useSources';
import Button from '../ui/Button';
import Card from '../ui/Card';
import EmptyState from '../ui/EmptyState';
import ErrorState from '../ui/ErrorState';
import LoadingState from '../ui/LoadingState';
import PageHeader from '../ui/PageHeader';
import StatusBadge from '../ui/StatusBadge';
import { formatTimestamp } from '../ui/formatTimestamp';
import SignalDetail from './SignalDetail';
import { useSignals, type Signal } from './useSignals';

/**
 * Review signals — the newest first, exactly as the backend returns them
 * (docs/02-functional-spec.md Section 9, workflow W6). `SignalResponse` currently exposes only
 * `id`, `relevantInformationId`, `state`, and timestamps — no title, summary, area, or source at the
 * list level; those are reached through the Relevant Information drill-down (`SignalDetail`), which
 * is the only place the backend actually exposes them.
 */
export default function SignalsPage() {
  const { state, refresh } = useSignals();
  const { state: areasState } = useAreasOfInterest();
  const { state: sourcesState } = useSources();
  const [selectedSignal, setSelectedSignal] = useState<Signal | null>(null);

  const areas = areasState.status === 'ready' ? areasState.areas : [];
  const sources = sourcesState.status === 'ready' ? sourcesState.sources : [];

  function handleFeedbackSubmitted(signalId: string, newState: Signal['state']) {
    setSelectedSignal((current) =>
      current && current.id === signalId ? { ...current, state: newState } : current,
    );
    void refresh();
  }

  if (selectedSignal) {
    return (
      <section aria-labelledby="signals-heading" className="page">
        <PageHeader id="signals-heading" title="Signals" />
        <SignalDetail
          signal={selectedSignal}
          areas={areas}
          sources={sources}
          onClose={() => setSelectedSignal(null)}
          onFeedbackSubmitted={handleFeedbackSubmitted}
        />
      </section>
    );
  }

  return (
    <section aria-labelledby="signals-heading" className="page">
      <PageHeader
        id="signals-heading"
        title="Signals"
        description="Notable developments Signal Engine has surfaced, newest first."
      />

      {state.status === 'loading' && <LoadingState label="Loading signals…" />}
      {state.status === 'error' && (
        <ErrorState error={state.error} onRetry={() => void refresh()} />
      )}
      {state.status === 'ready' && state.signals.length === 0 && (
        <EmptyState>
          No signals yet. Collection and processing may not have produced any yet.
        </EmptyState>
      )}
      {state.status === 'ready' && state.signals.length > 0 && (
        <Card padding="none">
          <div className="table-wrap">
            <table className="table">
              <caption>Signals, newest first</caption>
              <thead>
                <tr>
                  <th scope="col">Created</th>
                  <th scope="col">State</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {state.signals.map((signal) => (
                  <tr key={signal.id}>
                    <td>{formatTimestamp(signal.createdAt)}</td>
                    <td>
                      <StatusBadge status={signal.state} />
                    </td>
                    <td>
                      <Button
                        type="button"
                        variant="secondary"
                        size="sm"
                        onClick={() => setSelectedSignal(signal)}
                      >
                        View details
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </section>
  );
}
