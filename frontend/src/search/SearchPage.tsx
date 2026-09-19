import { useState, type FormEvent } from 'react';

import { useSources } from '../sources/useSources';
import Button from '../ui/Button';
import Card from '../ui/Card';
import EmptyState from '../ui/EmptyState';
import ErrorState from '../ui/ErrorState';
import LoadingState from '../ui/LoadingState';
import PageHeader from '../ui/PageHeader';
import PageInfoPanel from '../ui/PageInfoPanel';
import TextField from '../ui/TextField';
import SearchResultItem from './SearchResultItem';
import { useSearch } from './useSearch';

/**
 * Semantic search over the knowledge base (docs/02-functional-spec.md Section 12.3, workflow W9).
 * A thin presentation layer: the backend/RAG infrastructure performs retrieval; this screen only
 * submits the query and renders whatever it returns, in the order it returns it.
 */
export default function SearchPage() {
  const [query, setQuery] = useState('');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const { state, search } = useSearch();
  const { state: sourcesState } = useSources();
  const sources = sourcesState.status === 'ready' ? sourcesState.sources : [];

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (state.status === 'loading') {
      return; // guards against a duplicate submission while a search is already running
    }
    const trimmed = query.trim();
    if (!trimmed) {
      setFieldError('Enter a search query.');
      return;
    }
    setFieldError(null);
    void search(trimmed);
  }

  return (
    <section aria-labelledby="search-heading" className="page">
      <PageHeader
        id="search-heading"
        title="Search"
        description="Semantic search over everything Signal Engine has collected."
      />
      <PageInfoPanel
        whatItIs="Search looks through the knowledge base Signal Engine has processed and indexed. It does not search the open web."
        whyItExists="It helps you find previously collected information by meaning, not only by exact words."
        whatToExpect="Matching passages with their provenance, so you can follow each one back to its original source."
      />

      <Card>
        <form className="form" onSubmit={handleSubmit}>
          <TextField
            id="search-query"
            label="Search query"
            type="text"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            disabled={state.status === 'loading'}
          />
          <Button type="submit" variant="primary" disabled={state.status === 'loading'}>
            {state.status === 'loading' ? 'Searching…' : 'Search'}
          </Button>
        </form>

        {fieldError && (
          <p role="alert" className="alert">
            {fieldError}
          </p>
        )}
      </Card>

      {state.status === 'loading' && <LoadingState label="Searching…" />}
      {state.status === 'error' && (
        <ErrorState error={state.error} onRetry={() => void search(query.trim())} />
      )}
      {state.status === 'ready' && state.results.length === 0 && (
        <EmptyState>No results for this search.</EmptyState>
      )}
      {state.status === 'ready' && state.results.length > 0 && (
        <ul className="stack-list">
          {state.results.map((result) => (
            <SearchResultItem key={result.passageId} result={result} sources={sources} />
          ))}
        </ul>
      )}
    </section>
  );
}
