import Badge, { type BadgeTone } from './Badge';

/**
 * Maps the small set of status-like strings the backend actually returns — enabled/disabled,
 * signal states (docs/02-functional-spec.md Section 9.3), activity outcomes — to a badge tone,
 * keyed case-insensitively. The label is always rendered exactly as given: this never relabels or
 * reformats a backend value, only adds color.
 */
const TONE_BY_STATUS: Record<string, BadgeTone> = {
  enabled: 'success',
  disabled: 'neutral',
  new: 'info',
  reviewed: 'neutral',
  kept: 'success',
  dismissed: 'neutral',
  success: 'success',
  failure: 'danger',
};

export default function StatusBadge({ status }: { status: string }) {
  const tone = TONE_BY_STATUS[status.toLowerCase()] ?? 'neutral';
  return <Badge tone={tone}>{status}</Badge>;
}
