/**
 * Formats an ISO date-time string from the API for display, in the viewer's local timezone (no
 * timezone conversion is invented — this is the browser's own default `Date`/`toLocaleString`
 * behavior). Returns a placeholder for a missing timestamp rather than rendering "Invalid Date".
 */
export function formatTimestamp(value: string | undefined): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  return date.toLocaleString();
}
