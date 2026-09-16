/**
 * Request/response types for the backend endpoints the client currently supports.
 *
 * These are aliases onto the OpenAPI-generated schema components in `./generated/schema.ts` — never
 * hand-duplicated. Add an alias here only for a schema this client actually calls.
 */
import type { components } from './generated/schema';

export type SearchRequest = components['schemas']['SearchRequest'];
export type SearchResponse = components['schemas']['SearchResponse'];
export type SearchResultResponse = components['schemas']['SearchResultResponse'];
export type ProvenanceResponse = components['schemas']['ProvenanceResponse'];
export type QuestionRequest = components['schemas']['QuestionRequest'];
export type QuestionResponse = components['schemas']['QuestionResponse'];
export type CitationResponse = components['schemas']['CitationResponse'];

export type SourceConfigurationRequest = components['schemas']['SourceConfigurationRequest'];
export type SourceResponse = components['schemas']['SourceResponse'];

export type AddInterestRequest = components['schemas']['AddInterestRequest'];
export type UpdateInterestDescriptionRequest =
  components['schemas']['UpdateInterestDescriptionRequest'];
export type InterestResponse = components['schemas']['InterestResponse'];

export type AreaOfInterestResponse = components['schemas']['AreaOfInterestResponse'];

export type SignalResponse = components['schemas']['SignalResponse'];
/** Extracted from the generated `SignalResponse.state` union — not hand-duplicated. */
export type SignalState = NonNullable<SignalResponse['state']>;

export type RelevantInformationResponse = components['schemas']['RelevantInformationResponse'];
export type RawInformationItemResponse = components['schemas']['RawInformationItemResponse'];

export type SubmitFeedbackRequest = components['schemas']['SubmitFeedbackRequest'];
export type FeedbackResponse = components['schemas']['FeedbackResponse'];
/** Extracted from the generated `SubmitFeedbackRequest.verdict` union — not hand-duplicated. */
export type FeedbackVerdict = SubmitFeedbackRequest['verdict'];

export type ActivityRecordResponse = components['schemas']['ActivityRecordResponse'];

/**
 * The backend's RFC 9457 (`application/problem+json`) error body
 * (backend/src/main/java/org/signalengine/interfaces/rest/error/ApiExceptionHandler.java).
 *
 * Hand-written, not generated: the backend's current `@ApiResponse` annotations for 4xx/5xx
 * responses do not declare a distinct error schema (they reuse the success schema), so
 * `openapi-typescript` cannot represent this shape from the OpenAPI document. `type`, `title`,
 * `status`, `detail`, and `instance` are the standard RFC 9457 members; `code` and `correlationId`
 * are this backend's own extension members, set on every `/api/v1` error response
 * (`ApiExceptionHandler.problem(...)` / `.handleExceptionInternal(...)`).
 */
export interface ProblemDetail {
  type?: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  code: string;
  correlationId: string;
}
