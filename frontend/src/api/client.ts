import { apiV1BaseUrl } from '../config';
import { ApiNetworkError, ApiRequestError, ApiResponseShapeError } from './errors';
import type {
  ActivityRecordResponse,
  AddInterestRequest,
  AreaOfInterestResponse,
  FeedbackResponse,
  FeedbackVerdict,
  InterestResponse,
  ProblemDetail,
  QuestionRequest,
  QuestionResponse,
  RawInformationItemResponse,
  RelevantInformationResponse,
  SearchRequest,
  SearchResponse,
  SignalResponse,
  SourceConfigurationRequest,
  SourceResponse,
  UpdateInterestDescriptionRequest,
} from './types';

type HttpMethod = 'GET' | 'POST' | 'PUT';

/**
 * Sends one `/api/v1` request and returns the raw, successful {@link Response}, centralizing
 * request construction and network/HTTP-error handling so endpoint methods below do not repeat it.
 */
async function send(method: HttpMethod, path: string, body?: unknown): Promise<Response> {
  const init: RequestInit = { method };
  if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' };
    init.body = JSON.stringify(body);
  }

  let response: Response;
  try {
    response = await fetch(`${apiV1BaseUrl}${path}`, init);
  } catch (cause) {
    throw new ApiNetworkError(cause);
  }

  if (!response.ok) {
    throw new ApiRequestError(response.status, await parseProblemDetail(response));
  }
  return response;
}

/** Sends a request whose successful response body is a single JSON object. */
async function requestObject<TResponse>(
  method: HttpMethod,
  path: string,
  body?: unknown,
): Promise<TResponse> {
  return parseJsonObject<TResponse>(await send(method, path, body));
}

/** Sends a request whose successful response body is a JSON array. */
async function requestArray<TItem>(
  method: HttpMethod,
  path: string,
  body?: unknown,
): Promise<TItem[]> {
  return parseJsonArray<TItem>(await send(method, path, body));
}

/** Parses the backend's RFC 9457 problem body, tolerating a body that is not (valid) JSON. */
async function parseProblemDetail(response: Response): Promise<ProblemDetail> {
  try {
    const parsed: unknown = await response.json();
    if (isRecord(parsed)) {
      return {
        type: asOptionalString(parsed.type),
        title: asString(parsed.title, response.statusText || 'Request failed'),
        status: asNumber(parsed.status, response.status),
        detail: asString(parsed.detail, ''),
        instance: asOptionalString(parsed.instance),
        code: asString(parsed.code, 'UNKNOWN_ERROR'),
        correlationId: asString(parsed.correlationId, ''),
      };
    }
  } catch {
    // The error body was not JSON at all — fall through to the synthesized fallback below.
  }
  return {
    title: response.statusText || 'Request failed',
    status: response.status,
    detail: '',
    code: 'UNKNOWN_ERROR',
    correlationId: '',
  };
}

async function parseJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch (cause) {
    throw new ApiResponseShapeError('The response body was not valid JSON.', cause);
  }
}

async function parseJsonObject<T>(response: Response): Promise<T> {
  const parsed = await parseJson(response);
  if (!isRecord(parsed)) {
    throw new ApiResponseShapeError('Expected a JSON object in the response body.');
  }
  return parsed as T;
}

async function parseJsonArray<T>(response: Response): Promise<T[]> {
  const parsed = await parseJson(response);
  if (!Array.isArray(parsed)) {
    throw new ApiResponseShapeError('Expected a JSON array in the response body.');
  }
  return parsed as T[];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function asString(value: unknown, fallback: string): string {
  return typeof value === 'string' ? value : fallback;
}

function asOptionalString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function asNumber(value: unknown, fallback: number): number {
  return typeof value === 'number' ? value : fallback;
}

/** Typed client for the currently implemented `/api/v1` endpoints. */
export const api = {
  /** `POST /api/v1/search` — semantic search over the knowledge base. */
  search(request: SearchRequest): Promise<SearchResponse> {
    return requestObject<SearchResponse>('POST', '/search', request);
  },

  /** `POST /api/v1/questions` — grounded question answering over the knowledge base. */
  askQuestion(request: QuestionRequest): Promise<QuestionResponse> {
    return requestObject<QuestionResponse>('POST', '/questions', request);
  },

  /** `GET /api/v1/sources` — every configured source. */
  listSources(): Promise<SourceResponse[]> {
    return requestArray<SourceResponse>('GET', '/sources');
  },

  /** `POST /api/v1/sources` — register a new, enabled source. */
  createSource(configuration: SourceConfigurationRequest): Promise<SourceResponse> {
    return requestObject<SourceResponse>('POST', '/sources', configuration);
  },

  /** `PUT /api/v1/sources/{sourceId}` — edit a source's configuration. */
  updateSource(
    sourceId: string,
    configuration: SourceConfigurationRequest,
  ): Promise<SourceResponse> {
    return requestObject<SourceResponse>('PUT', `/sources/${sourceId}`, configuration);
  },

  /** `POST /api/v1/sources/{sourceId}/enable` */
  enableSource(sourceId: string): Promise<SourceResponse> {
    return requestObject<SourceResponse>('POST', `/sources/${sourceId}/enable`);
  },

  /** `POST /api/v1/sources/{sourceId}/disable` */
  disableSource(sourceId: string): Promise<SourceResponse> {
    return requestObject<SourceResponse>('POST', `/sources/${sourceId}/disable`);
  },

  /** `GET /api/v1/interests` — every configured interest, across all areas. */
  listInterests(): Promise<InterestResponse[]> {
    return requestArray<InterestResponse>('GET', '/interests');
  },

  /** `POST /api/v1/interests` — add a new, enabled interest under an area of interest. */
  createInterest(request: AddInterestRequest): Promise<InterestResponse> {
    return requestObject<InterestResponse>('POST', '/interests', request);
  },

  /** `PUT /api/v1/interests/{interestId}` — edit an interest's description. */
  updateInterest(
    interestId: string,
    request: UpdateInterestDescriptionRequest,
  ): Promise<InterestResponse> {
    return requestObject<InterestResponse>('PUT', `/interests/${interestId}`, request);
  },

  /** `POST /api/v1/interests/{interestId}/enable` */
  enableInterest(interestId: string): Promise<InterestResponse> {
    return requestObject<InterestResponse>('POST', `/interests/${interestId}/enable`);
  },

  /** `POST /api/v1/interests/{interestId}/disable` */
  disableInterest(interestId: string): Promise<InterestResponse> {
    return requestObject<InterestResponse>('POST', `/interests/${interestId}/disable`);
  },

  /** `GET /api/v1/areas-of-interest` — the six fixed areas of interest. */
  listAreasOfInterest(): Promise<AreaOfInterestResponse[]> {
    return requestArray<AreaOfInterestResponse>('GET', '/areas-of-interest');
  },

  /**
   * `GET /api/v1/signals` — the most recent signals, newest first. No `limit` is sent; the backend
   * applies its own default (50, bounded 1..200) — this client exposes no sorting/filtering/limit
   * controls (docs/02-functional-spec.md Section 9.4, workflow W6).
   */
  listSignals(): Promise<SignalResponse[]> {
    return requestArray<SignalResponse>('GET', '/signals');
  },

  /**
   * `GET /api/v1/relevant-information` — the most recent relevant-information records, newest
   * first, across every area — every retained record, whether or not it became a Signal. No
   * `limit` is sent; the backend applies its own default (50, bounded 1..200), the same convention
   * as `listSignals`. No area/interest filtering is exposed.
   */
  listRelevantInformation(): Promise<RelevantInformationResponse[]> {
    return requestArray<RelevantInformationResponse>('GET', '/relevant-information');
  },

  /** `GET /api/v1/relevant-information/{relevantInformationId}` */
  getRelevantInformation(relevantInformationId: string): Promise<RelevantInformationResponse> {
    return requestObject<RelevantInformationResponse>(
      'GET',
      `/relevant-information/${relevantInformationId}`,
    );
  },

  /**
   * `GET /api/v1/relevant-information/{relevantInformationId}/raw-information-items` — the
   * contributing raw items, which is where provenance (source, original link) actually lives; it is
   * not part of `RelevantInformationResponse` itself.
   */
  listContributingRawInformationItems(
    relevantInformationId: string,
  ): Promise<RawInformationItemResponse[]> {
    return requestArray<RawInformationItemResponse>(
      'GET',
      `/relevant-information/${relevantInformationId}/raw-information-items`,
    );
  },

  /**
   * `GET /api/v1/relevant-information/{relevantInformationId}/signal` — the Signal derived from
   * this record, if any. Most relevant information never becomes a Signal, so a 404 here is an
   * expected, normal outcome for callers to handle as "no signal" rather than as a failure.
   */
  getSignalForRelevantInformation(relevantInformationId: string): Promise<SignalResponse> {
    return requestObject<SignalResponse>(
      'GET',
      `/relevant-information/${relevantInformationId}/signal`,
    );
  },

  /**
   * `GET /api/v1/activity` — the most recent system activity, newest first. No `limit` is sent; the
   * backend applies its own default (50, bounded 1..200) — this client exposes no sorting/filtering/
   * limit controls (docs/02-functional-spec.md Section 15.1, workflow W11).
   */
  listActivity(): Promise<ActivityRecordResponse[]> {
    return requestArray<ActivityRecordResponse>('GET', '/activity');
  },

  /**
   * `POST /api/v1/signals/{signalId}/feedback` — record the user's relevant / not-relevant judgement
   * on a signal. Append-only on the backend: there is no update or withdrawal endpoint
   * (docs/02-functional-spec.md Section 10.2).
   */
  submitFeedback(signalId: string, verdict: FeedbackVerdict): Promise<FeedbackResponse> {
    return requestObject<FeedbackResponse>('POST', `/signals/${signalId}/feedback`, { verdict });
  },
};
