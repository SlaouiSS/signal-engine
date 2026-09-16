import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

import {
  api,
  ApiRequestError,
  type RawInformationItemResponse,
  type RelevantInformationResponse,
  type SignalResponse,
} from '../api';
import HomePage from './HomePage';

const areas = [
  { code: 'AI_AND_TECHNOLOGY', name: 'AI & Technology' },
  { code: 'MARKETS_AND_INVESTMENT', name: 'Markets & Investment' },
  { code: 'FASHION_AND_CLOTHING', name: 'Fashion & Clothing' },
];
const sourceOne = {
  id: 'src-1',
  type: 'rss',
  name: 'Example AI Feed',
  reference: 'https://example.test/ai-feed',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};
const sourceTwo = {
  id: 'src-2',
  type: 'rss',
  name: 'Example Markets Feed',
  reference: 'https://example.test/markets-feed',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};
const sourceThree = {
  id: 'src-3',
  type: 'rss',
  name: 'Example Fashion Feed',
  reference: 'https://example.test/fashion-feed',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

// Newest first, as the backend returns them — a signal-bearing AI item and a plain,
// no-signal Markets item, so tests can verify both areas and both card variants at once.
const aiItem: RelevantInformationResponse = {
  id: 'ri-2',
  reason: 'Matches AI & Technology interests',
  matchedAreaCodes: ['AI_AND_TECHNOLOGY'],
  matchedInterestIds: [],
  createdAt: '2026-02-02T00:00:00Z',
  updatedAt: '2026-02-02T00:00:00Z',
};
const marketsItem: RelevantInformationResponse = {
  id: 'ri-1',
  reason: 'ECB rate decision',
  matchedAreaCodes: ['MARKETS_AND_INVESTMENT'],
  matchedInterestIds: [],
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};
// No reason yet (relevance assessment hasn't enriched it) — exercises the "missing optional
// fields" path: no reason, and its raw item below carries no normalizedContent either.
const fashionItem: RelevantInformationResponse = {
  id: 'ri-3',
  reason: undefined,
  matchedAreaCodes: ['FASHION_AND_CLOTHING'],
  matchedInterestIds: [],
  createdAt: '2025-12-01T00:00:00Z',
  updatedAt: '2025-12-01T00:00:00Z',
};

const aiSignal: SignalResponse = {
  id: 'sig-2',
  relevantInformationId: 'ri-2',
  state: 'NEW',
  createdAt: '2026-02-02T01:00:00Z',
  updatedAt: '2026-02-02T01:00:00Z',
};

const aiRawItem = {
  id: 'raw-2',
  sourceId: 'src-1',
  originalUrl: 'https://example.test/articles/ai',
  normalizedContent:
    'Gemini Omni 1.1 Flash launches with faster reasoning\n\nGoogle DeepMind released a model update today, improving reasoning speed across benchmark tasks.',
  publishedAt: '2026-02-01T00:00:00Z',
  collectedAt: '2026-02-01T12:00:00Z',
  relevantInformationId: 'ri-2',
  createdAt: '2026-02-01T12:00:00Z',
  updatedAt: '2026-02-01T12:00:00Z',
};
const marketsRawItem = {
  id: 'raw-1',
  sourceId: 'src-2',
  originalUrl: 'https://example.test/articles/markets',
  normalizedContent:
    'ECB holds interest rates steady amid inflation concerns\n\nThe European Central Bank kept its benchmark rate unchanged Thursday, citing persistent inflation risks.',
  publishedAt: '2026-01-01T00:00:00Z',
  collectedAt: '2026-01-01T12:00:00Z',
  relevantInformationId: 'ri-1',
  createdAt: '2026-01-01T12:00:00Z',
  updatedAt: '2026-01-01T12:00:00Z',
};
// No normalizedContent at all — a raw item field that "may be null" (docs/05-data-model.md
// Section 8); the card must still render cleanly without a headline.
const fashionRawItem: RawInformationItemResponse = {
  id: 'raw-3',
  sourceId: 'src-3',
  originalUrl: 'https://example.test/articles/fashion',
  publishedAt: '2025-12-01T00:00:00Z',
  collectedAt: '2025-12-01T12:00:00Z',
  relevantInformationId: 'ri-3',
  createdAt: '2025-12-01T12:00:00Z',
  updatedAt: '2025-12-01T12:00:00Z',
};

function mockDetailAndSignalLookups() {
  const itemsById: Record<string, RelevantInformationResponse> = {
    'ri-2': aiItem,
    'ri-1': marketsItem,
    'ri-3': fashionItem,
  };
  const rawItemsById: Record<string, RawInformationItemResponse[]> = {
    'ri-2': [aiRawItem],
    'ri-1': [marketsRawItem],
    'ri-3': [fashionRawItem],
  };
  vi.spyOn(api, 'getRelevantInformation').mockImplementation((id) =>
    Promise.resolve(itemsById[id]),
  );
  vi.spyOn(api, 'listContributingRawInformationItems').mockImplementation((id) =>
    Promise.resolve(rawItemsById[id] ?? []),
  );
  vi.spyOn(api, 'getSignalForRelevantInformation').mockImplementation((id) => {
    if (id === 'ri-2') {
      return Promise.resolve(aiSignal);
    }
    return Promise.reject(
      new ApiRequestError(404, {
        title: 'Resource not found',
        status: 404,
        detail: 'no signal for this record',
        code: 'RESOURCE_NOT_FOUND',
        correlationId: 'c-signal',
      }),
    );
  });
}

beforeEach(() => {
  vi.spyOn(api, 'listAreasOfInterest').mockResolvedValue(areas);
  vi.spyOn(api, 'listSources').mockResolvedValue([sourceOne, sourceTwo, sourceThree]);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

test('shows a loading indicator before relevant information arrives', () => {
  vi.spyOn(api, 'listRelevantInformation').mockReturnValue(new Promise(() => {}));

  render(<HomePage />);

  expect(screen.getByRole('status').textContent).toMatch(/loading/i);
});

test('loads relevant information rather than signals', async () => {
  const listSignals = vi.spyOn(api, 'listSignals');
  vi.spyOn(api, 'listRelevantInformation').mockResolvedValue([aiItem, marketsItem]);
  mockDetailAndSignalLookups();

  render(<HomePage />);

  await screen.findByText('Matches AI & Technology interests');
  expect(api.listRelevantInformation).toHaveBeenCalled();
  expect(listSignals).not.toHaveBeenCalled();
});

test('renders items in the backend-provided (newest-first) order', async () => {
  vi.spyOn(api, 'listRelevantInformation').mockResolvedValue([aiItem, marketsItem]);
  mockDetailAndSignalLookups();

  render(<HomePage />);

  const items = await screen.findAllByRole('listitem');
  expect(items[0].textContent).toContain('Matches AI & Technology interests');
  expect(items[1].textContent).toContain('ECB rate decision');
});

test('shows an empty state when there is no relevant information', async () => {
  vi.spyOn(api, 'listRelevantInformation').mockResolvedValue([]);

  render(<HomePage />);

  expect(await screen.findByText(/has not surfaced any relevant information yet/i)).toBeDefined();
});

test('shows an error and allows retry when loading relevant information fails', async () => {
  vi.spyOn(api, 'listRelevantInformation')
    .mockRejectedValueOnce(
      new ApiRequestError(500, {
        title: 'Internal error',
        status: 500,
        detail: 'An unexpected error occurred.',
        code: 'INTERNAL_ERROR',
        correlationId: 'c-1',
      }),
    )
    .mockResolvedValueOnce([aiItem]);
  mockDetailAndSignalLookups();

  render(<HomePage />);

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('An unexpected error occurred.');

  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

  expect(await screen.findByText('Matches AI & Technology interests')).toBeDefined();
});

test('renders items from multiple areas with correct area names', async () => {
  vi.spyOn(api, 'listRelevantInformation').mockResolvedValue([aiItem, marketsItem]);
  mockDetailAndSignalLookups();

  render(<HomePage />);

  expect((await screen.findAllByText(/AI & Technology/)).length).toBeGreaterThan(0);
  expect(screen.getAllByText(/Markets & Investment/).length).toBeGreaterThan(0);
});

test('shows a meaningful headline derived from the raw item content, not just the source', async () => {
  vi.spyOn(api, 'listRelevantInformation').mockResolvedValue([aiItem]);
  mockDetailAndSignalLookups();

  render(<HomePage />);

  expect(
    await screen.findByText('Gemini Omni 1.1 Flash launches with faster reasoning'),
  ).toBeDefined();
  // The full body must not leak into the headline — only the title-shaped first line.
  expect(screen.queryByText(/Google DeepMind released a model update/)).toBeNull();
});

test('a non-signal item shows its headline and reason as meaningful information', async () => {
  vi.spyOn(api, 'listRelevantInformation').mockResolvedValue([marketsItem]);
  mockDetailAndSignalLookups();

  render(<HomePage />);

  expect(
    await screen.findByText('ECB holds interest rates steady amid inflation concerns'),
  ).toBeDefined();
  expect(screen.getByText('ECB rate decision')).toBeDefined();
  expect(screen.getByText('Relevant information')).toBeDefined();
  expect(screen.queryByText('Signal')).toBeNull();
  expect(screen.queryByRole('button', { name: 'View details' })).toBeNull();
});

test('a signal item still shows its headline, state, and reason', async () => {
  vi.spyOn(api, 'listRelevantInformation').mockResolvedValue([aiItem]);
  mockDetailAndSignalLookups();

  render(<HomePage />);

  expect(await screen.findByText('Signal')).toBeDefined();
  expect(screen.getByText('NEW')).toBeDefined();
  expect(screen.getByText('Gemini Omni 1.1 Flash launches with faster reasoning')).toBeDefined();
  expect(screen.getByText('Matches AI & Technology interests')).toBeDefined();
  expect(screen.getByRole('button', { name: 'View details' })).toBeDefined();
});

test('renders cleanly when the raw item has no normalized content and the record has no reason yet', async () => {
  vi.spyOn(api, 'listRelevantInformation').mockResolvedValue([fashionItem]);
  mockDetailAndSignalLookups();

  render(<HomePage />);

  // Source/link/area still render even with no headline and no reason to show.
  expect(await screen.findByText(/Example Fashion Feed/)).toBeDefined();
  expect(screen.getByText(/Fashion & Clothing/)).toBeDefined();
  expect(screen.getByRole('link', { name: 'Open original source' })).toBeDefined();
});

test('links to the original source through the existing external-link behavior', async () => {
  vi.spyOn(api, 'listRelevantInformation').mockResolvedValue([aiItem]);
  mockDetailAndSignalLookups();

  render(<HomePage />);

  const link = await screen.findByRole('link', { name: 'Open original source' });
  expect(link.getAttribute('href')).toBe('https://example.test/articles/ai');
  expect(link.getAttribute('target')).toBe('_blank');
  expect(link.getAttribute('rel')).toBe('noreferrer');
  expect(screen.getByText(/Example AI Feed/)).toBeDefined();
});

test('opens the existing signal detail view for a signal-bearing item and back again', async () => {
  vi.spyOn(api, 'listRelevantInformation').mockResolvedValue([aiItem]);
  mockDetailAndSignalLookups();

  render(<HomePage />);
  await screen.findByRole('button', { name: 'View details' });

  fireEvent.click(screen.getByRole('button', { name: 'View details' }));

  expect(screen.getByText('Signal details')).toBeDefined();
  expect(screen.getByText('Is this signal relevant?')).toBeDefined();

  fireEvent.click(screen.getByRole('button', { name: 'Back to signals' }));
  expect(await screen.findByRole('button', { name: 'View details' })).toBeDefined();
});
