import { resolveSourceLabel } from '../sources/resolveSourceLabel';
import { useSources } from '../sources/useSources';
import Card from '../ui/Card';
import EmptyState from '../ui/EmptyState';
import ErrorState from '../ui/ErrorState';
import LoadingState from '../ui/LoadingState';
import PageHeader from '../ui/PageHeader';
import PageInfoPanel from '../ui/PageInfoPanel';
import StatusBadge from '../ui/StatusBadge';
import { formatTimestamp } from '../ui/formatTimestamp';
import { useActivity } from './useActivity';

/**
 * Browse what the system did — the bounded activity feed (docs/02-functional-spec.md Section 15.1,
 * workflow W11). A flat, read-only list: the backend exposes no additional detail beyond what's
 * already in each record, so no drill-down is offered.
 */
export default function ActivityPage() {
  const { state, refresh } = useActivity();
  const { state: sourcesState } = useSources();
  const sources = sourcesState.status === 'ready' ? sourcesState.sources : [];

  return (
    <section aria-labelledby="activity-heading" className="page">
      <PageHeader
        id="activity-heading"
        title="Activity"
        description="What Signal Engine has done, newest first."
      />
      <PageInfoPanel
        whatItIs="A read-only list of recent operations Signal Engine has recorded, such as collecting and processing information."
        whyItExists="It lets you check what the system has been doing and whether any step failed."
        whatToExpect="Recent records with time, category, outcome, message and source. This is an operational log, not a full monitoring dashboard."
      />

      {state.status === 'loading' && <LoadingState label="Loading activity…" />}
      {state.status === 'error' && (
        <ErrorState error={state.error} onRetry={() => void refresh()} />
      )}
      {state.status === 'ready' && state.records.length === 0 && (
        <EmptyState>No activity recorded yet.</EmptyState>
      )}
      {state.status === 'ready' && state.records.length > 0 && (
        <Card padding="none">
          <div className="table-wrap">
            <table className="table">
              <caption>Recent activity, newest first</caption>
              <thead>
                <tr>
                  <th scope="col">Occurred</th>
                  <th scope="col">Category</th>
                  <th scope="col">Outcome</th>
                  <th scope="col">Message</th>
                  <th scope="col">Source</th>
                </tr>
              </thead>
              <tbody>
                {state.records.map((record) => (
                  <tr key={record.id}>
                    <td>{formatTimestamp(record.occurredAt)}</td>
                    <td>{record.category ?? '—'}</td>
                    <td>{record.outcome ? <StatusBadge status={record.outcome} /> : '—'}</td>
                    <td>
                      {record.message ?? '—'}
                      {record.rawInformationItemId && (
                        <>
                          {' '}
                          <span className="text-muted">(item {record.rawInformationItemId})</span>
                        </>
                      )}
                    </td>
                    <td>{record.sourceId ? resolveSourceLabel(record.sourceId, sources) : '—'}</td>
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
