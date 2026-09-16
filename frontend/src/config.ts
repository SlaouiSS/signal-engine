/**
 * Frontend runtime configuration.
 *
 * The app talks only to the backend REST API under `/api/v1` (docs/04-architecture.md
 * Section 10); it never accesses the database. The base URL is injected at build time
 * via `VITE_API_BASE_URL` and defaults to the local backend.
 */
export const apiBaseUrl: string = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8080';

/** Base URL for the versioned product API (backend `ApiV1.BASE_PATH`). */
export const apiV1BaseUrl: string = `${apiBaseUrl}/api/v1`;
