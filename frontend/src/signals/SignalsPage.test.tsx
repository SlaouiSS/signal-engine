import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

import { api, ApiRequestError, type SignalResponse } from '../api';
import SignalsPage from './SignalsPage';

const areas = [{ code: 'AI_AND_TECHNOLOGY', name: 'AI & Technology' }];
const source = {
  id: 'src-1',
  type: 'rss',
  name: 'Example Feed',
  reference: 'https://example.test/feed',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const newestSignal: SignalResponse = {
  id: 'sig-2',
  relevantInformationId: 'ri-2',
  state: 'NEW',
  createdAt: '2026-02-02T00:00:00Z',
  updatedAt: '2026-02-02T00:00:00Z',
};
const olderSignal: SignalResponse = {
  id: 'sig-1',
  relevantInformationId: 'ri-1',
  state: 'REVIEWED',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const relevantInformation = {
  id: 'ri-2',
  reason: 'Matches AI & Technology interests',
  matchedAreaCodes: ['AI_AND_TECHNOLOGY'],
  matchedInterestIds: [],
  createdAt: '2026-02-02T00:00:00Z',
  updatedAt: '2026-02-02T00:00:00Z',
};

const rawItem = {
  id: 'raw-1',
  sourceId: 'src-1',
  originalUrl: 'https://example.test/articles/1',
  publishedAt: '2026-02-01T00:00:00Z',
  collectedAt: '2026-02-01T12:00:00Z',
  relevantInformationId: 'ri-2',
  createdAt: '2026-02-01T12:00:00Z',
  updatedAt: '2026-02-01T12:00:00Z',
};

beforeEach(() => {
  vi.spyOn(api, 'listAreasOfInterest').mockResolvedValue(areas);
  vi.spyOn(api, 'listSources').mockResolvedValue([source]);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

test('shows a loading indicator before signals arrive', () => {
  vi.spyOn(api, 'listSignals').mockReturnValue(new Promise(() => {}));

  render(<SignalsPage />);

  expect(screen.getByRole('status').textContent).toMatch(/loading/i);
});

test('renders signals in the backend-provided (newest-first) order', async () => {
  vi.spyOn(api, 'listSignals').mockResolvedValue([newestSignal, olderSignal]);

  render(<SignalsPage />);

  const rows = await screen.findAllByRole('row');
  // rows[0] is the header row.
  expect(rows[1].textContent).toContain('NEW');
  expect(rows[2].textContent).toContain('REVIEWED');
});

test('shows an empty state when there are no signals', async () => {
  vi.spyOn(api, 'listSignals').mockResolvedValue([]);

  render(<SignalsPage />);

  expect(await screen.findByText(/no signals yet/i)).toBeDefined();
});

test('shows an error and allows retry when loading signals fails', async () => {
  vi.spyOn(api, 'listSignals')
    .mockRejectedValueOnce(
      new ApiRequestError(500, {
        title: 'Internal error',
        status: 500,
        detail: 'An unexpected error occurred.',
        code: 'INTERNAL_ERROR',
        correlationId: 'c-1',
      }),
    )
    .mockResolvedValueOnce([newestSignal]);

  render(<SignalsPage />);

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('An unexpected error occurred.');

  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

  expect(await screen.findByText('NEW')).toBeDefined();
});

test('opens a signal and shows its relevant-information detail with source and link', async () => {
  vi.spyOn(api, 'listSignals').mockResolvedValue([newestSignal]);
  vi.spyOn(api, 'getRelevantInformation').mockResolvedValue(relevantInformation);
  vi.spyOn(api, 'listContributingRawInformationItems').mockResolvedValue([rawItem]);

  render(<SignalsPage />);
  await screen.findByText('NEW');

  fireEvent.click(screen.getByRole('button', { name: 'View details' }));

  expect(await screen.findByText(/Matches AI & Technology interests/)).toBeDefined();
  expect(screen.getAllByText(/AI & Technology/).length).toBeGreaterThan(0);
  expect(screen.getByText('Example Feed')).toBeDefined();
  const link = screen.getByRole('link', { name: 'Open original source' });
  expect(link.getAttribute('href')).toBe('https://example.test/articles/1');
  expect(link.getAttribute('target')).toBe('_blank');
});

test('handles a raw item with no origin URI without rendering a broken link', async () => {
  vi.spyOn(api, 'listSignals').mockResolvedValue([newestSignal]);
  vi.spyOn(api, 'getRelevantInformation').mockResolvedValue(relevantInformation);
  vi.spyOn(api, 'listContributingRawInformationItems').mockResolvedValue([
    { ...rawItem, originalUrl: undefined },
  ]);

  render(<SignalsPage />);
  await screen.findByText('NEW');
  fireEvent.click(screen.getByRole('button', { name: 'View details' }));

  await screen.findByText('Example Feed');
  expect(screen.queryByRole('link', { name: 'Open original source' })).toBeNull();
});

test('shows an error when the relevant-information drill-down fails', async () => {
  vi.spyOn(api, 'listSignals').mockResolvedValue([newestSignal]);
  vi.spyOn(api, 'getRelevantInformation').mockRejectedValue(
    new ApiRequestError(404, {
      title: 'Resource not found',
      status: 404,
      detail: 'no relevant information',
      code: 'RESOURCE_NOT_FOUND',
      correlationId: 'c-2',
    }),
  );
  vi.spyOn(api, 'listContributingRawInformationItems').mockResolvedValue([]);

  render(<SignalsPage />);
  await screen.findByText('NEW');
  fireEvent.click(screen.getByRole('button', { name: 'View details' }));

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('no relevant information');
});

test('shows the feedback control, clearly tied to the open signal, while awaiting feedback', async () => {
  vi.spyOn(api, 'listSignals').mockResolvedValue([newestSignal]);
  vi.spyOn(api, 'getRelevantInformation').mockResolvedValue(relevantInformation);
  vi.spyOn(api, 'listContributingRawInformationItems').mockResolvedValue([rawItem]);

  render(<SignalsPage />);
  await screen.findByText('NEW');
  fireEvent.click(screen.getByRole('button', { name: 'View details' }));

  expect(await screen.findByText('Is this signal relevant?')).toBeDefined();
  expect(screen.getByRole('button', { name: 'Mark as relevant' })).toBeDefined();
  expect(screen.getByRole('button', { name: 'Mark as not relevant' })).toBeDefined();
  // Only the two backend-supported verdicts are offered — no other category, no free text, no rating.
  expect(screen.queryByRole('textbox')).toBeNull();
  expect(screen.queryByRole('spinbutton')).toBeNull();
});

test('submits relevant feedback for the open signal and reflects the acknowledged state', async () => {
  vi.spyOn(api, 'listSignals').mockResolvedValue([newestSignal]);
  vi.spyOn(api, 'getRelevantInformation').mockResolvedValue(relevantInformation);
  vi.spyOn(api, 'listContributingRawInformationItems').mockResolvedValue([rawItem]);
  const submitFeedback = vi.spyOn(api, 'submitFeedback').mockResolvedValue({
    id: 'fb-1',
    signalId: 'sig-2',
    verdict: 'RELEVANT',
    createdAt: '2026-02-03T00:00:00Z',
  });

  render(<SignalsPage />);
  await screen.findByText('NEW');
  fireEvent.click(screen.getByRole('button', { name: 'View details' }));
  await screen.findByRole('button', { name: 'Mark as relevant' });

  fireEvent.click(screen.getByRole('button', { name: 'Mark as relevant' }));

  expect(submitFeedback).toHaveBeenCalledWith('sig-2', 'RELEVANT');
  expect(
    await screen.findByText('Feedback recorded: this signal was marked relevant.'),
  ).toBeDefined();
  expect(screen.queryByRole('button', { name: 'Mark as relevant' })).toBeNull();
});

test('disables both feedback buttons and shows progress while a submission is in flight', async () => {
  vi.spyOn(api, 'listSignals').mockResolvedValue([newestSignal]);
  vi.spyOn(api, 'getRelevantInformation').mockResolvedValue(relevantInformation);
  vi.spyOn(api, 'listContributingRawInformationItems').mockResolvedValue([rawItem]);
  let resolveSubmit!: (value: {
    id: string;
    signalId: string;
    verdict: 'RELEVANT' | 'NOT_RELEVANT';
    createdAt: string;
  }) => void;
  const submitFeedback = vi
    .spyOn(api, 'submitFeedback')
    .mockReturnValue(new Promise((resolve) => (resolveSubmit = resolve)));

  render(<SignalsPage />);
  await screen.findByText('NEW');
  fireEvent.click(screen.getByRole('button', { name: 'View details' }));
  await screen.findByRole('button', { name: 'Mark as relevant' });

  fireEvent.click(screen.getByRole('button', { name: 'Mark as relevant' }));

  const submittingButton = await screen.findByRole('button', { name: 'Submitting…' });
  expect(submittingButton.hasAttribute('disabled')).toBe(true);
  expect(
    screen.getByRole('button', { name: 'Mark as not relevant' }).hasAttribute('disabled'),
  ).toBe(true);

  // A second click while in flight must not trigger a duplicate request.
  fireEvent.click(submittingButton);
  expect(submitFeedback).toHaveBeenCalledTimes(1);

  resolveSubmit({
    id: 'fb-1',
    signalId: 'sig-2',
    verdict: 'RELEVANT',
    createdAt: '2026-02-03T00:00:00Z',
  });
  await screen.findByText('Feedback recorded: this signal was marked relevant.');
});

test('shows an error and keeps the feedback control available when submission fails', async () => {
  vi.spyOn(api, 'listSignals').mockResolvedValue([newestSignal]);
  vi.spyOn(api, 'getRelevantInformation').mockResolvedValue(relevantInformation);
  vi.spyOn(api, 'listContributingRawInformationItems').mockResolvedValue([rawItem]);
  vi.spyOn(api, 'submitFeedback').mockRejectedValue(
    new ApiRequestError(400, {
      title: 'Invalid request',
      status: 400,
      detail: 'verdict must not be null',
      code: 'INVALID_INPUT',
      correlationId: 'c-3',
    }),
  );

  render(<SignalsPage />);
  await screen.findByText('NEW');
  fireEvent.click(screen.getByRole('button', { name: 'View details' }));
  await screen.findByRole('button', { name: 'Mark as relevant' });

  fireEvent.click(screen.getByRole('button', { name: 'Mark as relevant' }));

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('verdict must not be null');
  // The control remains available for another attempt — feedback was not silently discarded.
  expect(screen.getByRole('button', { name: 'Mark as relevant' })).toBeDefined();
});

test('shows the acknowledgment instead of feedback buttons once feedback has already been given', async () => {
  vi.spyOn(api, 'listSignals').mockResolvedValue([{ ...newestSignal, state: 'DISMISSED' }]);
  vi.spyOn(api, 'getRelevantInformation').mockResolvedValue(relevantInformation);
  vi.spyOn(api, 'listContributingRawInformationItems').mockResolvedValue([rawItem]);

  render(<SignalsPage />);
  await screen.findByText('DISMISSED');
  fireEvent.click(screen.getByRole('button', { name: 'View details' }));

  expect(
    await screen.findByText('Feedback recorded: this signal was marked not relevant.'),
  ).toBeDefined();
  expect(screen.queryByRole('button', { name: 'Mark as relevant' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Mark as not relevant' })).toBeNull();
});

test('returns to the signal list', async () => {
  vi.spyOn(api, 'listSignals').mockResolvedValue([newestSignal]);
  vi.spyOn(api, 'getRelevantInformation').mockResolvedValue(relevantInformation);
  vi.spyOn(api, 'listContributingRawInformationItems').mockResolvedValue([rawItem]);

  render(<SignalsPage />);
  await screen.findByText('NEW');
  fireEvent.click(screen.getByRole('button', { name: 'View details' }));
  await screen.findByText(/Matches AI & Technology interests/);

  fireEvent.click(screen.getByRole('button', { name: 'Back to signals' }));

  expect(await screen.findByRole('button', { name: 'View details' })).toBeDefined();
});
