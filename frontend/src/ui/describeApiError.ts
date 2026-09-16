import { ApiNetworkError, ApiRequestError, ApiResponseShapeError } from '../api';

/**
 * Turns any error the API client can throw into a short, user-facing message — never a raw stack
 * trace or technical detail the user cannot act on. Unknown errors get a generic fallback so a
 * mutation never fails silently.
 */
export function describeApiError(error: unknown): string {
  if (error instanceof ApiRequestError) {
    return error.problem.detail || error.problem.title;
  }
  if (error instanceof ApiNetworkError) {
    return 'Could not reach the backend. Check your connection and try again.';
  }
  if (error instanceof ApiResponseShapeError) {
    return 'The backend returned an unexpected response. Please try again.';
  }
  return 'Something went wrong. Please try again.';
}
