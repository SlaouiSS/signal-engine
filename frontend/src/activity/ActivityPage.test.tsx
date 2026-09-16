import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

import { api, ApiRequestError } from '../api';
import ActivityPage from './ActivityPage';

const source = {
  id: 'src-1',
  type: 'rss',
  name: 'Example Feed',
  reference: 'https://example.test/feed',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const newestRecord = {
  id: 'a2',
  occurredAt: '2026-02-02T00:00:00Z',
  category: 'collection',
  outcome: 'success',
  message: 'collected 3 item(s)',
  sourceId: 'src-1',
};
const olderRecord = {
  id: 'a1',
  occurredAt: '2026-01-01T00:00:00Z',
  category: 'maintenance',
  outcome: undefined,
  message: undefined,
  sourceId: undefined,
};

beforeEach(() => {
  vi.spyOn(api, 'listSources').mockResolvedValue([source]);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

test('shows a loading indicator before activity arrives', () => {
  vi.spyOn(api, 'listActivity').mockReturnValue(new Promise(() => {}));

  render(<ActivityPage />);

  expect(screen.getByRole('status').textContent).toMatch(/loading/i);
});

test('renders activity in the backend-provided (newest-first) order', async () => {
  vi.spyOn(api, 'listActivity').mockResolvedValue([newestRecord, olderRecord]);

  render(<ActivityPage />);

  const rows = await screen.findAllByRole('row');
  // rows[0] is the header row.
  expect(rows[1].textContent).toContain('collection');
  expect(rows[1].textContent).toContain('Example Feed');
  expect(rows[2].textContent).toContain('maintenance');
});

test('shows an empty state when there is no activity', async () => {
  vi.spyOn(api, 'listActivity').mockResolvedValue([]);

  render(<ActivityPage />);

  expect(await screen.findByText('No activity recorded yet.')).toBeDefined();
});

test('shows an error and allows retry when loading activity fails', async () => {
  vi.spyOn(api, 'listActivity')
    .mockRejectedValueOnce(
      new ApiRequestError(500, {
        title: 'Internal error',
        status: 500,
        detail: 'An unexpected error occurred.',
        code: 'INTERNAL_ERROR',
        correlationId: 'c-1',
      }),
    )
    .mockResolvedValueOnce([newestRecord]);

  render(<ActivityPage />);

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('An unexpected error occurred.');

  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

  expect(await screen.findByText('collection')).toBeDefined();
});

test('handles missing optional fields (system-level activity) safely', async () => {
  vi.spyOn(api, 'listActivity').mockResolvedValue([olderRecord]);

  render(<ActivityPage />);

  const rows = await screen.findAllByRole('row');
  expect(rows[1].textContent).toContain('maintenance');
  // outcome, message, and source are all absent for this record — rendered as a placeholder,
  // never as "undefined" or a crash.
  expect(rows[1].textContent).not.toContain('undefined');
});
