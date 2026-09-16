import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

import { api } from './api';
import App from './App';

beforeEach(() => {
  vi.spyOn(api, 'listSources').mockResolvedValue([]);
  vi.spyOn(api, 'listAreasOfInterest').mockResolvedValue([]);
  vi.spyOn(api, 'listInterests').mockResolvedValue([]);
  vi.spyOn(api, 'listRelevantInformation').mockResolvedValue([]);
  vi.spyOn(api, 'listSignals').mockResolvedValue([]);
  vi.spyOn(api, 'listActivity').mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

test('renders the application title', () => {
  render(<App />);

  expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Signal Engine');
});

test('shows Home as the default screen', async () => {
  render(<App />);

  expect(screen.getByRole('heading', { name: 'Home' })).toBeDefined();
  expect(screen.getByRole('button', { name: 'Home' }).getAttribute('aria-current')).toBe('page');
  await waitFor(() => expect(api.listRelevantInformation).toHaveBeenCalled());
});

test('navigates to the Sources screen and on to Interests', async () => {
  render(<App />);

  fireEvent.click(screen.getByRole('button', { name: 'Sources' }));

  expect(screen.getByRole('heading', { name: 'Sources' })).toBeDefined();
  await waitFor(() => expect(api.listSources).toHaveBeenCalled());

  fireEvent.click(screen.getByRole('button', { name: 'Interests' }));

  expect(screen.getByRole('heading', { name: 'Interests' })).toBeDefined();
  await waitFor(() => expect(api.listAreasOfInterest).toHaveBeenCalled());
});

test('navigates to the Signals screen', async () => {
  render(<App />);

  fireEvent.click(screen.getByRole('button', { name: 'Signals' }));

  expect(screen.getByRole('heading', { name: 'Signals' })).toBeDefined();
  await waitFor(() => expect(api.listSignals).toHaveBeenCalled());
});

test('navigates to the Search screen', () => {
  render(<App />);

  fireEvent.click(screen.getByRole('button', { name: 'Search' }));

  expect(screen.getByRole('heading', { name: 'Search' })).toBeDefined();
  expect(screen.getByLabelText('Search query')).toBeDefined();
});

test('navigates to the Q&A screen', () => {
  render(<App />);

  fireEvent.click(screen.getByRole('button', { name: 'Q&A' }));

  expect(screen.getByRole('heading', { name: 'Q&A' })).toBeDefined();
  expect(screen.getByLabelText('Question')).toBeDefined();
});

test('navigates to the Activity screen', async () => {
  render(<App />);

  fireEvent.click(screen.getByRole('button', { name: 'Activity' }));

  expect(screen.getByRole('heading', { name: 'Activity' })).toBeDefined();
  await waitFor(() => expect(api.listActivity).toHaveBeenCalled());
});
