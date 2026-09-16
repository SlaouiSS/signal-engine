import type { AreaOfInterest } from '../interests/useAreasOfInterest';
import type { Signal } from '../signals/useSignals';
import { useRelevantInformationDetail } from '../signals/useRelevantInformationDetail';
import { useSignalForRelevantInformation } from '../signals/useSignalForRelevantInformation';
import { resolveSourceLabel } from '../sources/resolveSourceLabel';
import type { Source } from '../sources/useSources';
import Badge from '../ui/Badge';
import Button from '../ui/Button';
import ErrorState from '../ui/ErrorState';
import ExternalLink from '../ui/ExternalLink';
import LoadingState from '../ui/LoadingState';
import StatusBadge from '../ui/StatusBadge';
import { formatTimestamp } from '../ui/formatTimestamp';
import { extractHeadline } from './extractHeadline';
import type { RelevantInformationItem } from './useRecentRelevantInformation';

interface HomeRelevantInformationCardProps {
  item: RelevantInformationItem;
  areas: AreaOfInterest[];
  sources: Source[];
  onSelectSignal: (signal: Signal) => void;
}

/**
 * One recent relevant-information record on Home. Content hierarchy: a headline derived from the
 * contributing raw item's own `normalizedContent` (real source text, never invented —
 * {@link extractHeadline}), then the relevant-information `reason` when the pipeline has produced
 * one, then matched area(s), then source + link last — what happened before where it came from.
 * The reason/areas are already on the list item itself; the headline and source still require the
 * existing contributing-raw-items drill-down (`useRelevantInformationDetail`, the same hook
 * `SignalDetail` uses). A Signal, if one was derived from this record, is looked up separately
 * (`useSignalForRelevantInformation`) and shown as a visual highlight with a "View details" action
 * that opens the existing `SignalDetail` view; a record without a Signal is still full Home
 * content, just without that highlight or action — reflecting that not every relevant thing is
 * important enough to become a Signal.
 */
export default function HomeRelevantInformationCard({
  item,
  areas,
  sources,
  onSelectSignal,
}: HomeRelevantInformationCardProps) {
  const { state: detailState, refresh: refreshDetail } = useRelevantInformationDetail(item.id);
  const { state: signalState } = useSignalForRelevantInformation(item.id);

  const primaryRawItem =
    detailState.status === 'ready' ? detailState.rawInformationItems[0] : undefined;
  const headline = extractHeadline(primaryRawItem?.normalizedContent);
  const matchedAreaNames = (item.matchedAreaCodes ?? []).map((code) =>
    resolveAreaName(code, areas),
  );
  // A signal-lookup failure degrades to "no signal shown" rather than its own error box: the
  // record's own content below is unaffected and remains fully useful on its own.
  const signal = signalState.status === 'ready' ? signalState.signal : null;

  return (
    <li>
      <div className="item-row">
        <div className="item-row-primary">
          {signal ? (
            <>
              <Badge tone="info">Signal</Badge>
              <StatusBadge status={signal.state} />
            </>
          ) : (
            <span className="text-muted">Relevant information</span>
          )}
          <span className="text-secondary">{formatTimestamp(item.createdAt)}</span>
        </div>
        {signal && (
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => onSelectSignal(signal)}
          >
            View details
          </Button>
        )}
      </div>

      <div className="card-section">
        {detailState.status === 'loading' && <LoadingState label="Loading details…" />}
        {detailState.status === 'error' && (
          <ErrorState error={detailState.error} onRetry={() => void refreshDetail()} />
        )}

        {headline && <p>{headline}</p>}
        {item.reason && <p className="text-secondary">{item.reason}</p>}

        {matchedAreaNames.length > 0 && (
          <p className="text-secondary">Area(s): {matchedAreaNames.join(', ')}</p>
        )}

        {primaryRawItem && (
          <p className="text-secondary">
            {resolveSourceLabel(primaryRawItem.sourceId, sources)}
            {primaryRawItem.originalUrl && (
              <>
                {' — '}
                <ExternalLink href={primaryRawItem.originalUrl}>Open original source</ExternalLink>
              </>
            )}
          </p>
        )}
      </div>
    </li>
  );
}

function resolveAreaName(code: string, areas: AreaOfInterest[]): string {
  return areas.find((area) => area.code === code)?.name ?? code;
}
