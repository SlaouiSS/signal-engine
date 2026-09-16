import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

import { apiV1BaseUrl } from '../config';
import { api } from './client';
import { ApiNetworkError, ApiRequestError, ApiResponseShapeError } from './errors';
import type { QuestionResponse, SearchResponse } from './types';

function jsonResponse(body: unknown, init?: ResponseInit): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
}

function problemResponse(status: number, problem: Record<string, unknown>): Response {
  return new Response(JSON.stringify(problem), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

describe('api client', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  test('builds the request URL from the configured API v1 base URL', async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(jsonResponse({ results: [] } satisfies SearchResponse));

    await api.search({ query: 'acme earnings' });

    expect(fetchMock).toHaveBeenCalledWith(`${apiV1BaseUrl}/search`, expect.anything());
  });

  test('serializes the request body as JSON with the expected headers and method', async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(jsonResponse({ results: [] } satisfies SearchResponse));

    await api.search({ query: 'acme earnings', topK: 3 });

    const [, requestInit] = fetchMock.mock.calls[0]!;
    expect(requestInit).toMatchObject({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    });
    expect(JSON.parse(requestInit!.body as string)).toEqual({
      query: 'acme earnings',
      topK: 3,
    });
  });

  test('returns the typed search response on success', async () => {
    const body: SearchResponse = {
      results: [
        {
          passageId: 'p1',
          text: 'passage text',
          score: 0.87,
          provenance: {
            sourceId: 'feed-1',
            originUri: 'https://example.test/a',
            title: 'Title',
            documentId: 'doc-1',
          },
        },
      ],
    };
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(body));

    const result = await api.search({ query: 'acme earnings' });

    expect(result).toEqual(body);
  });

  test('returns the typed question response, preserving citations and provenance', async () => {
    const body: QuestionResponse = {
      answered: true,
      answer: 'Revenue grew 10%.',
      citations: [
        {
          passageId: 'p1',
          // Provenance fields the backend has no value for (originUri/title/documentId) are simply
          // absent, matching the generated schema's optional (`?`) fields.
          provenance: { sourceId: 'feed-1' },
          quotedText: 'revenue grew 10%',
        },
      ],
    };
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(body));

    const result = await api.askQuestion({ question: 'how did revenue change?' });

    expect(result).toEqual(body);
    expect(fetch).toHaveBeenCalledWith(`${apiV1BaseUrl}/questions`, expect.anything());
  });

  test('throws ApiNetworkError when the request cannot reach the backend', async () => {
    const networkFailure = new TypeError('Failed to fetch');
    vi.mocked(fetch).mockRejectedValueOnce(networkFailure);

    const failure = await api.search({ query: 'acme earnings' }).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiNetworkError);
    expect((failure as ApiNetworkError).cause).toBe(networkFailure);
  });

  test('throws ApiRequestError with the backend problem+json body on a 400 response', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      problemResponse(400, {
        type: 'about:blank',
        title: 'Invalid input',
        status: 400,
        detail: 'search query must not be blank',
        code: 'INVALID_INPUT',
        correlationId: 'c-1',
      }),
    );

    const failure = await api.search({ query: 'x' }).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiRequestError);
    const requestError = failure as ApiRequestError;
    expect(requestError.status).toBe(400);
    expect(requestError.problem).toEqual({
      type: 'about:blank',
      title: 'Invalid input',
      status: 400,
      detail: 'search query must not be blank',
      instance: undefined,
      code: 'INVALID_INPUT',
      correlationId: 'c-1',
    });
  });

  test('throws ApiRequestError for a 503 AI-capability-unavailable response', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      problemResponse(503, {
        title: 'AI capability unavailable',
        status: 503,
        detail: 'Search or question answering is temporarily unavailable.',
        code: 'AI_CAPABILITY_UNAVAILABLE',
        correlationId: 'c-2',
      }),
    );

    const failure = await api
      .askQuestion({ question: 'a question' })
      .catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiRequestError);
    expect((failure as ApiRequestError).problem.code).toBe('AI_CAPABILITY_UNAVAILABLE');
    expect((failure as ApiRequestError).problem.correlationId).toBe('c-2');
  });

  test('falls back to a synthesized problem when an error response has no JSON body', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response('Bad Gateway', { status: 502, statusText: 'Bad Gateway' }),
    );

    const failure = await api.search({ query: 'x' }).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiRequestError);
    const requestError = failure as ApiRequestError;
    expect(requestError.status).toBe(502);
    expect(requestError.problem.code).toBe('UNKNOWN_ERROR');
  });

  test('throws ApiResponseShapeError when a successful response body is not JSON', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response('not json', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );

    await expect(api.search({ query: 'x' })).rejects.toBeInstanceOf(ApiResponseShapeError);
  });

  test('throws ApiResponseShapeError when a successful response body is not an object', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(['unexpected', 'array']));

    await expect(api.search({ query: 'x' })).rejects.toBeInstanceOf(ApiResponseShapeError);
  });

  test('listSignals calls GET /signals with no query parameters', async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(jsonResponse([]));

    await api.listSignals();

    const [url, requestInit] = fetchMock.mock.calls[0]!;
    expect(url).toBe(`${apiV1BaseUrl}/signals`);
    expect(requestInit).toMatchObject({ method: 'GET' });
  });

  test('listSignals returns the typed signal list, in the order the backend returned it', async () => {
    const signals = [
      {
        id: 's2',
        relevantInformationId: 'ri2',
        state: 'NEW',
        createdAt: '2026-02-02T00:00:00Z',
        updatedAt: '2026-02-02T00:00:00Z',
      },
      {
        id: 's1',
        relevantInformationId: 'ri1',
        state: 'REVIEWED',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    ];
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(signals));

    const result = await api.listSignals();

    expect(result).toEqual(signals);
  });

  test('getRelevantInformation calls GET /relevant-information/{id}', async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(
      jsonResponse({
        id: 'ri1',
        reason: 'relevant',
        matchedAreaCodes: [],
        matchedInterestIds: [],
      }),
    );

    await api.getRelevantInformation('ri1');

    expect(fetchMock).toHaveBeenCalledWith(
      `${apiV1BaseUrl}/relevant-information/ri1`,
      expect.objectContaining({ method: 'GET' }),
    );
  });

  test('listContributingRawInformationItems calls the raw-information-items sub-resource', async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(jsonResponse([]));

    await api.listContributingRawInformationItems('ri1');

    expect(fetchMock).toHaveBeenCalledWith(
      `${apiV1BaseUrl}/relevant-information/ri1/raw-information-items`,
      expect.objectContaining({ method: 'GET' }),
    );
  });

  test('submitFeedback posts the verdict to the signal-specific feedback endpoint', async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(
      jsonResponse(
        {
          id: 'fb-1',
          signalId: 'sig-1',
          verdict: 'RELEVANT',
          createdAt: '2026-02-03T00:00:00Z',
        },
        { status: 201 },
      ),
    );

    const result = await api.submitFeedback('sig-1', 'RELEVANT');

    expect(fetchMock).toHaveBeenCalledWith(`${apiV1BaseUrl}/signals/sig-1/feedback`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ verdict: 'RELEVANT' }),
    });
    expect(result).toEqual({
      id: 'fb-1',
      signalId: 'sig-1',
      verdict: 'RELEVANT',
      createdAt: '2026-02-03T00:00:00Z',
    });
  });

  test('listActivity calls GET /activity with no query parameters', async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(jsonResponse([]));

    await api.listActivity();

    const [url, requestInit] = fetchMock.mock.calls[0]!;
    expect(url).toBe(`${apiV1BaseUrl}/activity`);
    expect(requestInit).toMatchObject({ method: 'GET' });
  });
});
