import { useState, type FormEvent } from 'react';

import { useSources } from '../sources/useSources';
import Button from '../ui/Button';
import Card from '../ui/Card';
import ErrorState from '../ui/ErrorState';
import LoadingState from '../ui/LoadingState';
import PageHeader from '../ui/PageHeader';
import PageInfoPanel from '../ui/PageInfoPanel';
import TextAreaField from '../ui/TextAreaField';
import CitationItem from './CitationItem';
import { useAskQuestion } from './useAskQuestion';

/**
 * Grounded question answering over the knowledge base (docs/02-functional-spec.md Section 13,
 * workflow W10). A single question, a single grounded answer, and its citations — not a
 * conversation. The backend performs retrieval, grounding, and generation; this only submits the
 * question and renders what comes back.
 */
export default function QaPage() {
  const [question, setQuestion] = useState('');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const { state, ask } = useAskQuestion();
  const { state: sourcesState } = useSources();
  const sources = sourcesState.status === 'ready' ? sourcesState.sources : [];

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (state.status === 'loading') {
      return; // guards against a duplicate submission while a question is already running
    }
    const trimmed = question.trim();
    if (!trimmed) {
      setFieldError('Enter a question.');
      return;
    }
    setFieldError(null);
    void ask(trimmed);
  }

  return (
    <section aria-labelledby="qa-heading" className="page">
      <PageHeader
        id="qa-heading"
        title="Q&A"
        description="Ask a question and get a source-grounded answer from the knowledge base."
      />
      <PageInfoPanel
        whatItIs="Q&A answers a question using only the information in the indexed knowledge base. It is not a general-purpose chatbot."
        whyItExists="It gives you an answer grounded in what Signal Engine has collected, instead of an answer from the model's own knowledge."
        whatToExpect="An answer with citations to its sources. If the knowledge base does not contain enough information, Signal Engine says so."
      />

      <Card>
        <form className="form" onSubmit={handleSubmit}>
          <TextAreaField
            id="qa-question"
            label="Question"
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            disabled={state.status === 'loading'}
          />
          <Button type="submit" variant="primary" disabled={state.status === 'loading'}>
            {state.status === 'loading' ? 'Asking…' : 'Ask'}
          </Button>
        </form>

        {fieldError && (
          <p role="alert" className="alert">
            {fieldError}
          </p>
        )}

        {state.status === 'idle' && (
          <p className="text-secondary">
            Ask a natural-language question about the information available in Signal Engine.
          </p>
        )}
      </Card>

      {state.status === 'loading' && <LoadingState label="Asking…" />}

      {state.status === 'error' && (
        <ErrorState error={state.error} onRetry={() => void ask(question.trim())} />
      )}

      {state.status === 'ready' && (
        <Card as="article" aria-labelledby="qa-answer-heading">
          <p className="text-secondary">
            <strong>Question:</strong> {state.question}
          </p>

          {state.answered ? (
            <div className="card-section">
              <h3 id="qa-answer-heading" className="section-title">
                Answer
              </h3>
              <p>{state.answer}</p>
            </div>
          ) : (
            <div className="card-section">
              <h3 id="qa-answer-heading" className="section-title">
                Not enough information
              </h3>
              <p>{state.answer}</p>
            </div>
          )}

          {state.citations.length > 0 && (
            <div className="card-section">
              <h4>Sources</h4>
              <ul className="stack-list">
                {state.citations.map((citation) => (
                  <CitationItem key={citation.passageId} citation={citation} sources={sources} />
                ))}
              </ul>
            </div>
          )}
        </Card>
      )}
    </section>
  );
}
