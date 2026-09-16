import { useState } from 'react';

import { useAreasOfInterest } from '../interests/useAreasOfInterest';
import SignalDetail from '../signals/SignalDetail';
import type { Signal } from '../signals/useSignals';
import { useSources } from '../sources/useSources';
import EmptyState from '../ui/EmptyState';
import ErrorState from '../ui/ErrorState';
import LoadingState from '../ui/LoadingState';
import PageHeader from '../ui/PageHeader';
import HomeRelevantInformationCard from './HomeRelevantInformationCard';
import { useRecentRelevantInformation } from './useRecentRelevantInformation';

/**
 * The main dashboard: what's new and relevant across every configured area of interest, newest
 * first, exactly as `GET /api/v1/relevant-information` returns it — no frontend sorting,
 * filtering, or area balancing. This is deliberately broader than the Signals page
 * (`GET /api/v1/signals`, the complete signal history): a relevant-information record that never
 * became a Signal is still real, retained knowledge and belongs on Home, just without the Signal
 * highlight. Reuses `useRecentRelevantInformation`/`HomeRelevantInformationCard` for the feed and,
 * when a record has a Signal, the same `SignalDetail` drill-down the Signals page uses — so Home
 * shows real content without a second representation of either domain.
 */
export default function HomePage() {
  const { state, refresh } = useRecentRelevantInformation();
  const { state: areasState } = useAreasOfInterest();
  const { state: sourcesState } = useSources();
  const [selectedSignal, setSelectedSignal] = useState<Signal | null>(null);
  // Bumped whenever feedback changes a signal's state, and mixed into each card's key below, so
  // the affected card remounts and its own signal lookup (useSignalForRelevantInformation) picks
  // up the new state on return — that per-card hook has no other way to know it went stale.
  const [signalRefreshToken, setSignalRefreshToken] = useState(0);

  const areas = areasState.status === 'ready' ? areasState.areas : [];
  const sources = sourcesState.status === 'ready' ? sourcesState.sources : [];

  function handleFeedbackSubmitted(signalId: string, newState: Signal['state']) {
    setSelectedSignal((current) =>
      current && current.id === signalId ? { ...current, state: newState } : current,
    );
    setSignalRefreshToken((token) => token + 1);
  }

  if (selectedSignal) {
    return (
      <section aria-labelledby="home-heading" className="page">
        <PageHeader id="home-heading" title="Home" />
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
    <section aria-labelledby="home-heading" className="page">
      <PageHeader
        id="home-heading"
        title="Home"
        description="What's new and worth knowing right now."
      />

      {state.status === 'loading' && <LoadingState label="Loading recent information…" />}
      {state.status === 'error' && (
        <ErrorState error={state.error} onRetry={() => void refresh()} />
      )}
      {state.status === 'ready' && state.items.length === 0 && (
        <EmptyState>
          Signal Engine has not surfaced any relevant information yet. Once sources are collected
          and processed, what's new across your areas of interest will appear here.
        </EmptyState>
      )}
      {state.status === 'ready' && state.items.length > 0 && (
        <ul className="stack-list">
          {state.items.map((item) => (
            <HomeRelevantInformationCard
              key={`${item.id}-${signalRefreshToken}`}
              item={item}
              areas={areas}
              sources={sources}
              onSelectSignal={setSelectedSignal}
            />
          ))}
        </ul>
      )}
    </section>
  );
}
