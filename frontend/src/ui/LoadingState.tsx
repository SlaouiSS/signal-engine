/**
 * A consistent, accessible loading indicator — `role="status"` so assistive technology announces
 * it, exactly as the plain `<p role="status">` it replaces did. `label` is rendered verbatim so
 * existing copy (and the tests matching it) is preserved.
 */
export default function LoadingState({ label }: { label: string }) {
  return (
    <p role="status" className="status-message">
      <span className="spinner" aria-hidden="true" />
      {label}
    </p>
  );
}
