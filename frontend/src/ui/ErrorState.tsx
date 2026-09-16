import Button from './Button';
import ErrorMessage from './ErrorMessage';

/**
 * The failed-request + retry pattern repeated on every page: an accessible error message
 * (`role="alert"`, via `ErrorMessage`) alongside a "Retry" action. Renders nothing when `error` is
 * falsy, matching `ErrorMessage`'s own behavior.
 */
export default function ErrorState({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  if (!error) {
    return null;
  }
  return (
    <div className="error-state">
      <ErrorMessage error={error} />
      <Button type="button" variant="danger" size="sm" onClick={onRetry}>
        Retry
      </Button>
    </div>
  );
}
