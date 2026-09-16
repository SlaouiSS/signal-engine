import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

import { api, ApiRequestError, type QuestionResponse } from '../api';
import QaPage from './QaPage';

const source = {
  id: 'src-1',
  type: 'rss',
  name: 'Example Feed',
  reference: 'https://example.test/feed',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const answeredResponse: QuestionResponse = {
  answered: true,
  answer: 'The EU adopted the AI Act, a landmark regulation.',
  citations: [
    {
      passageId: 'p1',
      quotedText: 'the AI Act entered into force',
      provenance: {
        sourceId: 'src-1',
        originUri: 'https://example.test/articles/1',
        title: 'AI Act overview',
        documentId: 'doc-1',
      },
    },
  ],
};

const unansweredResponse: QuestionResponse = {
  answered: false,
  answer: 'Not enough information in the knowledge base to answer this question.',
  citations: [],
};

beforeEach(() => {
  vi.spyOn(api, 'listSources').mockResolvedValue([source]);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

test('shows the initial state before any question is asked', () => {
  render(<QaPage />);

  expect(screen.getByLabelText('Question')).toBeDefined();
  expect(screen.getByRole('button', { name: 'Ask' })).toBeDefined();
  expect(screen.getByText(/ask a natural-language question/i)).toBeDefined();
});

test('rejects a blank question without calling the API', () => {
  const askQuestion = vi.spyOn(api, 'askQuestion');
  render(<QaPage />);

  fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

  expect(screen.getByRole('alert').textContent).toMatch(/enter a question/i);
  expect(askQuestion).not.toHaveBeenCalled();
});

test('rejects a whitespace-only question without calling the API', () => {
  const askQuestion = vi.spyOn(api, 'askQuestion');
  render(<QaPage />);

  fireEvent.change(screen.getByLabelText('Question'), { target: { value: '   ' } });
  fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

  expect(screen.getByRole('alert').textContent).toMatch(/enter a question/i);
  expect(askQuestion).not.toHaveBeenCalled();
});

test('submits the trimmed question through the existing API client', async () => {
  const askQuestion = vi.spyOn(api, 'askQuestion').mockResolvedValue(unansweredResponse);
  render(<QaPage />);

  fireEvent.change(screen.getByLabelText('Question'), {
    target: { value: '  how did revenue change?  ' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

  await screen.findByText('Not enough information in the knowledge base to answer this question.');
  expect(askQuestion).toHaveBeenCalledWith({ question: 'how did revenue change?' });
});

test('shows a loading state while the question is running', () => {
  vi.spyOn(api, 'askQuestion').mockReturnValue(new Promise(() => {}));
  render(<QaPage />);

  fireEvent.change(screen.getByLabelText('Question'), { target: { value: 'a question' } });
  fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

  expect(screen.getByRole('status').textContent).toMatch(/asking/i);
  expect(screen.getByRole('button', { name: 'Asking…' })).toHaveProperty('disabled', true);
});

test('displays the answer and its citations when answered', async () => {
  vi.spyOn(api, 'askQuestion').mockResolvedValue(answeredResponse);
  render(<QaPage />);

  fireEvent.change(screen.getByLabelText('Question'), {
    target: { value: 'what happened with the AI Act?' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

  expect(
    await screen.findByText('The EU adopted the AI Act, a landmark regulation.'),
  ).toBeDefined();
  const article = screen.getByRole('article');
  expect(within(article).getByText(/what happened with the AI Act\?/)).toBeDefined();
  expect(within(article).getByText(/the AI Act entered into force/)).toBeDefined();
  expect(within(article).getByText(/Example Feed/)).toBeDefined();
  expect(within(article).getByText(/AI Act overview/)).toBeDefined();
  const link = screen.getByRole('link', { name: 'Open original source' });
  expect(link.getAttribute('href')).toBe('https://example.test/articles/1');
  expect(link.getAttribute('target')).toBe('_blank');
});

test('clearly reports an unanswerable question without fabricating an answer', async () => {
  vi.spyOn(api, 'askQuestion').mockResolvedValue(unansweredResponse);
  render(<QaPage />);

  fireEvent.change(screen.getByLabelText('Question'), {
    target: { value: 'what will the stock price be tomorrow?' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

  expect(
    await screen.findByText(
      'Not enough information in the knowledge base to answer this question.',
    ),
  ).toBeDefined();
  expect(screen.getByRole('heading', { name: 'Not enough information' })).toBeDefined();
  expect(screen.queryByRole('list')).toBeNull();
});

test('handles a citation with missing provenance fields safely', async () => {
  vi.spyOn(api, 'askQuestion').mockResolvedValue({
    answered: true,
    answer: 'Answer text.',
    citations: [{ passageId: 'p1', provenance: { sourceId: 'src-1' } }],
  });
  render(<QaPage />);

  fireEvent.change(screen.getByLabelText('Question'), { target: { value: 'a question' } });
  fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

  await screen.findByText('Answer text.');
  expect(screen.queryByRole('link', { name: 'Open original source' })).toBeNull();
});

test('shows an error and allows retry on a backend failure', async () => {
  vi.spyOn(api, 'askQuestion')
    .mockRejectedValueOnce(
      new ApiRequestError(503, {
        title: 'AI capability unavailable',
        status: 503,
        detail: 'Search or question answering is temporarily unavailable.',
        code: 'AI_CAPABILITY_UNAVAILABLE',
        correlationId: 'c-1',
      }),
    )
    .mockResolvedValueOnce(answeredResponse);
  render(<QaPage />);

  fireEvent.change(screen.getByLabelText('Question'), { target: { value: 'a question' } });
  fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('Search or question answering is temporarily unavailable.');

  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

  expect(
    await screen.findByText('The EU adopted the AI Act, a landmark regulation.'),
  ).toBeDefined();
});
