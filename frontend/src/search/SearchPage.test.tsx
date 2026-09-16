import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

import { api, ApiRequestError, type SearchResponse } from '../api';
import SearchPage from './SearchPage';

const source = {
  id: 'src-1',
  type: 'rss',
  name: 'Example Feed',
  reference: 'https://example.test/feed',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const firstResult = {
  passageId: 'p1',
  text: 'Grid-scale batteries smoothed the evening peak last quarter.',
  score: 0.912345,
  provenance: {
    sourceId: 'src-1',
    originUri: 'https://example.test/articles/1',
    title: 'Grid batteries',
    documentId: 'doc-1',
  },
};
const secondResult = {
  passageId: 'p2',
  text: 'Permitting now takes nine months on average.',
  score: 0.8,
  provenance: { sourceId: 'src-1' },
};

beforeEach(() => {
  vi.spyOn(api, 'listSources').mockResolvedValue([source]);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

test('renders the search form', () => {
  render(<SearchPage />);

  expect(screen.getByLabelText('Search query')).toBeDefined();
  expect(screen.getByRole('button', { name: 'Search' })).toBeDefined();
});

test('rejects a blank query without calling the API', () => {
  const search = vi.spyOn(api, 'search');
  render(<SearchPage />);

  fireEvent.click(screen.getByRole('button', { name: 'Search' }));

  expect(screen.getByRole('alert').textContent).toMatch(/enter a search query/i);
  expect(search).not.toHaveBeenCalled();
});

test('rejects a whitespace-only query without calling the API', () => {
  const search = vi.spyOn(api, 'search');
  render(<SearchPage />);

  fireEvent.change(screen.getByLabelText('Search query'), { target: { value: '   ' } });
  fireEvent.click(screen.getByRole('button', { name: 'Search' }));

  expect(screen.getByRole('alert').textContent).toMatch(/enter a search query/i);
  expect(search).not.toHaveBeenCalled();
});

test('submits the trimmed query through the existing API client', async () => {
  const search = vi.spyOn(api, 'search').mockResolvedValue({ results: [] });
  render(<SearchPage />);

  fireEvent.change(screen.getByLabelText('Search query'), {
    target: { value: '  grid batteries  ' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Search' }));

  expect(await screen.findByText('No results for this search.')).toBeDefined();
  expect(search).toHaveBeenCalledWith({ query: 'grid batteries' });
});

test('shows a loading state while the search is running', () => {
  vi.spyOn(api, 'search').mockReturnValue(new Promise(() => {}));
  render(<SearchPage />);

  fireEvent.change(screen.getByLabelText('Search query'), { target: { value: 'grid batteries' } });
  fireEvent.click(screen.getByRole('button', { name: 'Search' }));

  expect(screen.getByRole('status').textContent).toMatch(/searching/i);
  expect(screen.getByRole('button', { name: 'Searching…' })).toHaveProperty('disabled', true);
});

test('renders results in the exact order the backend returned them', async () => {
  const response: SearchResponse = { results: [firstResult, secondResult] };
  vi.spyOn(api, 'search').mockResolvedValue(response);
  render(<SearchPage />);

  fireEvent.change(screen.getByLabelText('Search query'), { target: { value: 'grid batteries' } });
  fireEvent.click(screen.getByRole('button', { name: 'Search' }));

  const items = await screen.findAllByRole('listitem');
  expect(items).toHaveLength(2);
  expect(items[0].textContent).toContain('Grid-scale batteries');
  expect(items[1].textContent).toContain('Permitting now takes');
});

test('displays the score accurately without inventing a rating', async () => {
  vi.spyOn(api, 'search').mockResolvedValue({ results: [firstResult] });
  render(<SearchPage />);

  fireEvent.change(screen.getByLabelText('Search query'), { target: { value: 'grid batteries' } });
  fireEvent.click(screen.getByRole('button', { name: 'Search' }));

  expect(await screen.findByText('Score: 0.912')).toBeDefined();
});

test('displays provenance and an original-source link when available', async () => {
  vi.spyOn(api, 'search').mockResolvedValue({ results: [firstResult] });
  render(<SearchPage />);

  fireEvent.change(screen.getByLabelText('Search query'), { target: { value: 'grid batteries' } });
  fireEvent.click(screen.getByRole('button', { name: 'Search' }));

  expect(await screen.findByText(/Example Feed/)).toBeDefined();
  expect(screen.getByText(/Grid batteries/)).toBeDefined();
  const link = screen.getByRole('link', { name: 'Open original source' });
  expect(link.getAttribute('href')).toBe('https://example.test/articles/1');
  expect(link.getAttribute('target')).toBe('_blank');
});

test('handles missing provenance fields safely, without a broken link', async () => {
  vi.spyOn(api, 'search').mockResolvedValue({ results: [secondResult] });
  render(<SearchPage />);

  fireEvent.change(screen.getByLabelText('Search query'), { target: { value: 'permitting' } });
  fireEvent.click(screen.getByRole('button', { name: 'Search' }));

  await screen.findByText(/Permitting now takes/);
  expect(screen.queryByRole('link', { name: 'Open original source' })).toBeNull();
});

test('shows an explicit empty state, not a fabricated answer, when there are no results', async () => {
  vi.spyOn(api, 'search').mockResolvedValue({ results: [] });
  render(<SearchPage />);

  fireEvent.change(screen.getByLabelText('Search query'), { target: { value: 'no matches' } });
  fireEvent.click(screen.getByRole('button', { name: 'Search' }));

  expect(await screen.findByText('No results for this search.')).toBeDefined();
});

test('shows an error and allows retry on a backend failure', async () => {
  vi.spyOn(api, 'search')
    .mockRejectedValueOnce(
      new ApiRequestError(503, {
        title: 'AI capability unavailable',
        status: 503,
        detail: 'Search or question answering is temporarily unavailable.',
        code: 'AI_CAPABILITY_UNAVAILABLE',
        correlationId: 'c-1',
      }),
    )
    .mockResolvedValueOnce({ results: [firstResult] });
  render(<SearchPage />);

  fireEvent.change(screen.getByLabelText('Search query'), { target: { value: 'grid batteries' } });
  fireEvent.click(screen.getByRole('button', { name: 'Search' }));

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('Search or question answering is temporarily unavailable.');

  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

  expect(await screen.findByText(/Grid-scale batteries/)).toBeDefined();
});
