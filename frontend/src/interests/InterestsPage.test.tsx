import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';

import { api, ApiRequestError } from '../api';
import InterestsPage from './InterestsPage';

const areas = [
  { code: 'AI_AND_TECHNOLOGY', name: 'AI & Technology' },
  { code: 'MARKETS_AND_INVESTMENT', name: 'Markets & Investment' },
];

const interest = {
  id: 'i1',
  areaOfInterestCode: 'AI_AND_TECHNOLOGY',
  description: 'EU AI Act developments',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

test('shows a loading indicator before areas and interests arrive', () => {
  vi.spyOn(api, 'listAreasOfInterest').mockReturnValue(new Promise(() => {}));
  vi.spyOn(api, 'listInterests').mockReturnValue(new Promise(() => {}));

  render(<InterestsPage />);

  expect(screen.getByRole('status').textContent).toMatch(/loading/i);
});

test('renders the six areas and interests are grouped under their area', async () => {
  vi.spyOn(api, 'listAreasOfInterest').mockResolvedValue(areas);
  vi.spyOn(api, 'listInterests').mockResolvedValue([interest]);

  render(<InterestsPage />);

  expect(await screen.findByRole('heading', { name: 'AI & Technology' })).toBeDefined();
  expect(screen.getByRole('heading', { name: 'Markets & Investment' })).toBeDefined();
  expect(screen.getByText('EU AI Act developments')).toBeDefined();
});

test('shows an empty state for an area with no interests', async () => {
  vi.spyOn(api, 'listAreasOfInterest').mockResolvedValue(areas);
  vi.spyOn(api, 'listInterests').mockResolvedValue([]);

  render(<InterestsPage />);

  const marketsSection = (
    await screen.findByRole('heading', { name: 'Markets & Investment' })
  ).closest('div')!;
  expect(
    within(marketsSection).getByText('No interests configured yet in this area.'),
  ).toBeDefined();
});

test('shows an error and allows retry when loading areas fails', async () => {
  vi.spyOn(api, 'listAreasOfInterest')
    .mockRejectedValueOnce(
      new ApiRequestError(503, {
        title: 'AI capability unavailable',
        status: 503,
        detail: 'temporarily unavailable',
        code: 'AI_CAPABILITY_UNAVAILABLE',
        correlationId: 'c-1',
      }),
    )
    .mockResolvedValueOnce(areas);
  vi.spyOn(api, 'listInterests').mockResolvedValue([]);

  render(<InterestsPage />);

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('temporarily unavailable');

  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

  expect(await screen.findByRole('heading', { name: 'AI & Technology' })).toBeDefined();
});

test('shows an error and allows retry when loading interests fails', async () => {
  vi.spyOn(api, 'listAreasOfInterest').mockResolvedValue(areas);
  vi.spyOn(api, 'listInterests')
    .mockRejectedValueOnce(
      new ApiRequestError(500, {
        title: 'Internal error',
        status: 500,
        detail: 'An unexpected error occurred.',
        code: 'INTERNAL_ERROR',
        correlationId: 'c-2',
      }),
    )
    .mockResolvedValueOnce([interest]);

  render(<InterestsPage />);

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('An unexpected error occurred.');

  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

  expect(await screen.findByText('EU AI Act developments')).toBeDefined();
});

test('creates an interest under the area it was added from', async () => {
  vi.spyOn(api, 'listAreasOfInterest').mockResolvedValue(areas);
  vi.spyOn(api, 'listInterests').mockResolvedValueOnce([]).mockResolvedValueOnce([interest]);
  const createInterest = vi.spyOn(api, 'createInterest').mockResolvedValue(interest);

  render(<InterestsPage />);
  await screen.findByRole('heading', { name: 'AI & Technology' });

  const aiSection = screen.getByRole('heading', { name: 'AI & Technology' }).closest('div')!;
  fireEvent.click(within(aiSection).getByRole('button', { name: 'Add interest' }));
  fireEvent.change(screen.getByLabelText('Description'), {
    target: { value: 'EU AI Act developments' },
  });
  fireEvent.click(within(aiSection).getByRole('button', { name: 'Add interest' }));

  await waitFor(() =>
    expect(createInterest).toHaveBeenCalledWith({
      areaOfInterestCode: 'AI_AND_TECHNOLOGY',
      description: 'EU AI Act developments',
    }),
  );
  expect(await screen.findByText('EU AI Act developments')).toBeDefined();
});

test('rejects an empty description without calling the API', async () => {
  vi.spyOn(api, 'listAreasOfInterest').mockResolvedValue(areas);
  vi.spyOn(api, 'listInterests').mockResolvedValue([]);
  const createInterest = vi.spyOn(api, 'createInterest');

  render(<InterestsPage />);
  await screen.findByRole('heading', { name: 'AI & Technology' });

  const aiSection = screen.getByRole('heading', { name: 'AI & Technology' }).closest('div')!;
  fireEvent.click(within(aiSection).getByRole('button', { name: 'Add interest' }));
  fireEvent.click(within(aiSection).getByRole('button', { name: 'Add interest' }));

  expect(within(aiSection).getByRole('alert').textContent).toMatch(/required/i);
  expect(createInterest).not.toHaveBeenCalled();
});

test('edits an interest description only, keeping its area', async () => {
  const updated = { ...interest, description: 'Updated description' };
  vi.spyOn(api, 'listAreasOfInterest').mockResolvedValue(areas);
  vi.spyOn(api, 'listInterests').mockResolvedValueOnce([interest]).mockResolvedValueOnce([updated]);
  const updateInterest = vi.spyOn(api, 'updateInterest').mockResolvedValue(updated);

  render(<InterestsPage />);
  await screen.findByText('EU AI Act developments');

  fireEvent.click(screen.getByRole('button', { name: 'Edit interest "EU AI Act developments"' }));
  fireEvent.change(screen.getByLabelText('Description'), {
    target: { value: 'Updated description' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

  await waitFor(() =>
    expect(updateInterest).toHaveBeenCalledWith('i1', { description: 'Updated description' }),
  );
  expect(await screen.findByText('Updated description')).toBeDefined();
});

test('disables an enabled interest', async () => {
  const disabled = { ...interest, enabled: false };
  vi.spyOn(api, 'listAreasOfInterest').mockResolvedValue(areas);
  vi.spyOn(api, 'listInterests')
    .mockResolvedValueOnce([interest])
    .mockResolvedValueOnce([disabled]);
  const disableInterest = vi.spyOn(api, 'disableInterest').mockResolvedValue(disabled);

  render(<InterestsPage />);
  await screen.findByText('EU AI Act developments');

  fireEvent.click(
    screen.getByRole('button', { name: 'Disable interest "EU AI Act developments"' }),
  );

  await waitFor(() => expect(disableInterest).toHaveBeenCalledWith('i1'));
  expect(await screen.findByText('Disabled')).toBeDefined();
});
