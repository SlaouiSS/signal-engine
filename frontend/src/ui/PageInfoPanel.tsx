export interface PageInfoPanelProps {
  /** What this section is. */
  whatItIs: string;
  /** Why this section exists. */
  whyItExists: string;
  /** What Signal Engine currently shows or does here. */
  whatToExpect: string;
}

/**
 * A short, always-visible explanation of the current section: what it is, why it exists, and what
 * to expect. Static copy supplied by each page — it reads no data and makes no API calls.
 */
export default function PageInfoPanel({ whatItIs, whyItExists, whatToExpect }: PageInfoPanelProps) {
  return (
    <aside className="info-panel" aria-label="About this section">
      <p className="info-panel-heading">
        <span className="info-panel-icon" aria-hidden="true">
          i
        </span>
        What is this?
      </p>
      <p>{whatItIs}</p>
      <p>{whyItExists}</p>
      <p>
        <strong>What to expect:</strong> {whatToExpect}
      </p>
    </aside>
  );
}
