import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';

import { api, ApiRequestError } from '../api';
import SourcesPage from './SourcesPage';

const source = {
  id: 's1',
  type: 'rss',
  name: 'Example Feed',
  reference: 'https://example.test/feed',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

test('shows a loading indicator before sources arrive', () => {
  vi.spyOn(api, 'listSources').mockReturnValue(new Promise(() => {}));

  render(<SourcesPage />);

  expect(screen.getByRole('status').textContent).toMatch(/loading/i);
});

test('renders the configured sources once loaded', async () => {
  vi.spyOn(api, 'listSources').mockResolvedValue([source]);

  render(<SourcesPage />);

  expect(await screen.findByText('Example Feed')).toBeDefined();
  expect(screen.getByText('rss')).toBeDefined();
  expect(screen.getByText('Enabled')).toBeDefined();
  expect(screen.getByRole('link', { name: source.reference })).toBeDefined();
});

test('shows an empty state when no sources are configured', async () => {
  vi.spyOn(api, 'listSources').mockResolvedValue([]);

  render(<SourcesPage />);

  expect(await screen.findByText('No sources configured yet.')).toBeDefined();
});

test('shows an error and allows retry when loading sources fails', async () => {
  vi.spyOn(api, 'listSources')
    .mockRejectedValueOnce(
      new ApiRequestError(500, {
        title: 'Internal error',
        status: 500,
        detail: 'An unexpected error occurred.',
        code: 'INTERNAL_ERROR',
        correlationId: 'c-1',
      }),
    )
    .mockResolvedValueOnce([source]);

  render(<SourcesPage />);

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('An unexpected error occurred.');

  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

  expect(await screen.findByText('Example Feed')).toBeDefined();
});

test('creates a source and refreshes the list', async () => {
  vi.spyOn(api, 'listSources').mockResolvedValueOnce([]).mockResolvedValueOnce([source]);
  const createSource = vi.spyOn(api, 'createSource').mockResolvedValue(source);

  render(<SourcesPage />);
  await screen.findByText('No sources configured yet.');

  fireEvent.click(screen.getByRole('button', { name: 'Add source' }));
  fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'rss' } });
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Example Feed' } });
  fireEvent.change(screen.getByLabelText('Reference'), {
    target: { value: 'https://example.test/feed' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Add source' }));

  await waitFor(() =>
    expect(createSource).toHaveBeenCalledWith({
      type: 'rss',
      name: 'Example Feed',
      reference: 'https://example.test/feed',
    }),
  );
  expect(await screen.findByText('Example Feed')).toBeDefined();
});

test('rejects an empty field without calling the API', () => {
  vi.spyOn(api, 'listSources').mockResolvedValue([]);
  const createSource = vi.spyOn(api, 'createSource');

  render(<SourcesPage />);

  fireEvent.click(screen.getByRole('button', { name: 'Add source' }));
  fireEvent.click(screen.getByRole('button', { name: 'Add source' }));

  expect(screen.getByRole('alert').textContent).toMatch(/required/i);
  expect(createSource).not.toHaveBeenCalled();
});

test('edits an existing source', async () => {
  const updated = { ...source, name: 'Renamed Feed' };
  vi.spyOn(api, 'listSources').mockResolvedValueOnce([source]).mockResolvedValueOnce([updated]);
  const updateSource = vi.spyOn(api, 'updateSource').mockResolvedValue(updated);

  render(<SourcesPage />);
  await screen.findByText('Example Feed');

  fireEvent.click(screen.getByRole('button', { name: 'Edit source "Example Feed"' }));
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Renamed Feed' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

  await waitFor(() =>
    expect(updateSource).toHaveBeenCalledWith('s1', {
      type: 'rss',
      name: 'Renamed Feed',
      reference: 'https://example.test/feed',
    }),
  );
  expect(await screen.findByText('Renamed Feed')).toBeDefined();
});

test('disables an enabled source', async () => {
  const disabled = { ...source, enabled: false };
  vi.spyOn(api, 'listSources').mockResolvedValueOnce([source]).mockResolvedValueOnce([disabled]);
  const disableSource = vi.spyOn(api, 'disableSource').mockResolvedValue(disabled);

  render(<SourcesPage />);
  await screen.findByText('Example Feed');

  fireEvent.click(screen.getByRole('button', { name: 'Disable source "Example Feed"' }));

  await waitFor(() => expect(disableSource).toHaveBeenCalledWith('s1'));
  expect(await screen.findByText('Disabled')).toBeDefined();
});

test('shows an error when a toggle fails, without losing the current list', async () => {
  vi.spyOn(api, 'listSources').mockResolvedValue([source]);
  vi.spyOn(api, 'disableSource').mockRejectedValue(
    new ApiRequestError(404, {
      title: 'Resource not found',
      status: 404,
      detail: 'no source',
      code: 'RESOURCE_NOT_FOUND',
      correlationId: 'c-2',
    }),
  );

  render(<SourcesPage />);
  await screen.findByText('Example Feed');

  fireEvent.click(screen.getByRole('button', { name: 'Disable source "Example Feed"' }));

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('no source');
  expect(screen.getByText('Example Feed')).toBeDefined();
});
