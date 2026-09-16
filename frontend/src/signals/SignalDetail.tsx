import { useState } from 'react';

import { api, type FeedbackVerdict } from '../api';
import type { AreaOfInterest } from '../interests/useAreasOfInterest';
import { resolveSourceLabel } from '../sources/resolveSourceLabel';
import type { Source } from '../sources/useSources';
import Button from '../ui/Button';
import Card from '../ui/Card';
import EmptyState from '../ui/EmptyState';
import ErrorMessage from '../ui/ErrorMessage';
import ErrorState from '../ui/ErrorState';
import ExternalLink from '../ui/ExternalLink';
import LoadingState from '../ui/LoadingState';
import StatusBadge from '../ui/StatusBadge';
import { formatTimestamp } from '../ui/formatTimestamp';
import type { Signal } from './useSignals';
import { useRelevantInformationDetail } from './useRelevantInformationDetail';

interface SignalDetailProps {
  signal: Signal;
  /** Already-loaded areas/sources from the page, reused rather than re-fetched per signal. */
  areas: AreaOfInterest[];
  sources: Source[];
  onClose: () => void;
  /**
   * Called once feedback has been recorded, with the signal's new state — derived from the
   * documented RELEVANT→KEPT / NOT_RELEVANT→DISMISSED mapping
   * (`DefaultSubmitFeedbackUseCase`), since `FeedbackResponse` does not include the updated signal.
   */
  onFeedbackSubmitted: (signalId: string, newState: Signal['state']) => void;
}

/** The signal states that mean "feedback has not been recorded yet" (docs/02-functional-spec.md Section 9.3). */
const AWAITING_FEEDBACK_STATES: ReadonlySet<Signal['state']> = new Set(['NEW', 'REVIEWED']);

/**
 * The Relevant Information drill-down for one signal (docs/02-functional-spec.md Section 9.2, 9.4),
 * plus the feedback control (Section 10.2): mark the signal relevant or not relevant. Feedback is
 * append-only and cannot be changed once given — this control disappears once `signal.state` reflects
 * a recorded verdict (`KEPT`/`DISMISSED`), since those states are set only by feedback submission.
 * The original source remains authoritative: this only links out to it, never reproduces it.
 */
export default function SignalDetail({
  signal,
  areas,
  sources,
  onClose,
  onFeedbackSubmitted,
}: SignalDetailProps) {
  const { state, refresh } = useRelevantInformationDetail(signal.relevantInformationId);
  const [submittingVerdict, setSubmittingVerdict] = useState<FeedbackVerdict | null>(null);
  const [feedbackError, setFeedbackError] = useState<unknown>(null);

  async function submitFeedback(verdict: FeedbackVerdict) {
    if (submittingVerdict) {
      return;
    }
    setFeedbackError(null);
    setSubmittingVerdict(verdict);
    try {
      await api.submitFeedback(signal.id, verdict);
      onFeedbackSubmitted(signal.id, verdict === 'RELEVANT' ? 'KEPT' : 'DISMISSED');
    } catch (error) {
      setFeedbackError(error);
    } finally {
      setSubmittingVerdict(null);
    }
  }

  return (
    <Card as="section" aria-labelledby="signal-detail-heading">
      <h3 id="signal-detail-heading" className="section-title">
        Signal details
      </h3>
      <dl className="meta-list">
        <dt>State</dt>
        <dd>
          <StatusBadge status={signal.state} />
        </dd>
        <dt>Created</dt>
        <dd>{formatTimestamp(signal.createdAt)}</dd>
      </dl>

      <section aria-labelledby="signal-feedback-heading" className="card-section">
        <h4 id="signal-feedback-heading">Feedback</h4>
        {AWAITING_FEEDBACK_STATES.has(signal.state) ? (
          <>
            <p>Is this signal relevant?</p>
            <div className="btn-group">
              <Button
                type="button"
                variant="primary"
                onClick={() => void submitFeedback('RELEVANT')}
                disabled={submittingVerdict !== null}
              >
                {submittingVerdict === 'RELEVANT' ? 'Submitting…' : 'Mark as relevant'}
              </Button>
              <Button
                type="button"
                variant="secondary"
                onClick={() => void submitFeedback('NOT_RELEVANT')}
                disabled={submittingVerdict !== null}
              >
                {submittingVerdict === 'NOT_RELEVANT' ? 'Submitting…' : 'Mark as not relevant'}
              </Button>
            </div>
            <ErrorMessage error={feedbackError} />
          </>
        ) : (
          <p>
            Feedback recorded: this signal was marked{' '}
            {signal.state === 'KEPT' ? 'relevant' : 'not relevant'}.
          </p>
        )}
      </section>

      {state.status === 'loading' && <LoadingState label="Loading details…" />}
      {state.status === 'error' && (
        <ErrorState error={state.error} onRetry={() => void refresh()} />
      )}
      {state.status === 'ready' && (
        <div className="card-section">
          {state.relevantInformation.reason && (
            <p>
              <strong>Why it matters:</strong> {state.relevantInformation.reason}
            </p>
          )}

          {state.relevantInformation.matchedAreaCodes &&
            state.relevantInformation.matchedAreaCodes.length > 0 && (
              <p>
                <strong>Area(s):</strong>{' '}
                {state.relevantInformation.matchedAreaCodes
                  .map((code) => resolveAreaName(code, areas))
                  .join(', ')}
              </p>
            )}

          <h4>Sources</h4>
          {state.rawInformationItems.length === 0 && (
            <EmptyState>No source details available.</EmptyState>
          )}
          {state.rawInformationItems.length > 0 && (
            <ul className="stack-list">
              {state.rawInformationItems.map((item) => (
                <li key={item.id}>
                  <span>{resolveSourceLabel(item.sourceId, sources)}</span>
                  {item.publishedAt && (
                    <span className="text-secondary">
                      {' '}
                      — published {formatTimestamp(item.publishedAt)}
                    </span>
                  )}{' '}
                  <ExternalLink href={item.originalUrl}>Open original source</ExternalLink>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <div className="card-section">
        <Button type="button" variant="ghost" onClick={onClose}>
          Back to signals
        </Button>
      </div>
    </Card>
  );
}

function resolveAreaName(code: string, areas: AreaOfInterest[]): string {
  return areas.find((area) => area.code === code)?.name ?? code;
}
