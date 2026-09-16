import { describeApiError } from './describeApiError';

/** An accessible, non-technical error banner for a failed API call. Renders nothing when idle. */
export default function ErrorMessage({ error }: { error: unknown }) {
  if (!error) {
    return null;
  }
  return (
    <p role="alert" className="alert">
      {describeApiError(error)}
    </p>
  );
}
