import type { ProblemDetail } from './types';

/** The request could not reach the backend at all (offline, DNS failure, CORS, timeout...). */
export class ApiNetworkError extends Error {
  constructor(cause: unknown) {
    super('Could not reach the backend API.', { cause });
    this.name = 'ApiNetworkError';
  }
}

/** The backend responded with an HTTP error status, carrying its RFC 9457 problem body. */
export class ApiRequestError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail;

  constructor(status: number, problem: ProblemDetail) {
    super(problem.detail || problem.title);
    this.name = 'ApiRequestError';
    this.status = status;
    this.problem = problem;
  }
}

/** A response the client could parse as JSON did not have the shape this client expects. */
export class ApiResponseShapeError extends Error {
  constructor(message: string, cause?: unknown) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = 'ApiResponseShapeError';
  }
}

/** Every error this API client can throw. */
export type ApiError = ApiNetworkError | ApiRequestError | ApiResponseShapeError;
