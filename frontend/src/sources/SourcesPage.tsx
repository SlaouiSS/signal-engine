import { useState } from 'react';

import { api } from '../api';
import Button from '../ui/Button';
import Card from '../ui/Card';
import EmptyState from '../ui/EmptyState';
import ErrorMessage from '../ui/ErrorMessage';
import ErrorState from '../ui/ErrorState';
import LoadingState from '../ui/LoadingState';
import PageHeader from '../ui/PageHeader';
import PageInfoPanel from '../ui/PageInfoPanel';
import StatusBadge from '../ui/StatusBadge';
import SourceForm from './SourceForm';
import { useSources, type Source } from './useSources';

type FormState = { mode: 'closed' } | { mode: 'create' } | { mode: 'edit'; source: Source };

/**
 * Configure the curated sources Signal Engine collects from (docs/02-functional-spec.md Section 4,
 * workflow W1). Sources are enabled/disabled rather than removed — the backend has no delete
 * operation for a source (see `ManageSourcesUseCase`'s documented Q2/Q3 open questions).
 */
export default function SourcesPage() {
  const { state, refresh } = useSources();
  const [formState, setFormState] = useState<FormState>({ mode: 'closed' });
  const [togglingId, setTogglingId] = useState<string | null>(null);
  const [toggleError, setToggleError] = useState<unknown>(null);

  function closeForm() {
    setFormState({ mode: 'closed' });
  }

  function handleFormSuccess() {
    closeForm();
    void refresh();
  }

  async function toggleEnabled(source: Source) {
    setToggleError(null);
    setTogglingId(source.id);
    try {
      if (source.enabled) {
        await api.disableSource(source.id);
      } else {
        await api.enableSource(source.id);
      }
      await refresh();
    } catch (error) {
      setToggleError(error);
    } finally {
      setTogglingId(null);
    }
  }

  return (
    <section aria-labelledby="sources-heading" className="page">
      <PageHeader
        id="sources-heading"
        title="Sources"
        description="The feeds and origins Signal Engine collects information from."
        actions={
          formState.mode === 'closed' && (
            <Button
              type="button"
              variant="primary"
              onClick={() => setFormState({ mode: 'create' })}
            >
              Add source
            </Button>
          )
        }
      />
      <PageInfoPanel
        whatItIs="Sources are the websites and feeds Signal Engine collects information from."
        whyItExists="Only enabled sources feed the processing pipeline, so this list controls what information can enter Signal Engine."
        whatToExpect="Add, edit, enable or disable a source. Collection is not scheduled, so sources are not monitored continuously."
      />

      {formState.mode === 'create' && (
        <Card>
          <SourceForm onCancel={closeForm} onSuccess={handleFormSuccess} />
        </Card>
      )}
      {formState.mode === 'edit' && (
        <Card>
          <SourceForm
            source={formState.source}
            onCancel={closeForm}
            onSuccess={handleFormSuccess}
          />
        </Card>
      )}

      <ErrorMessage error={toggleError} />

      {state.status === 'loading' && <LoadingState label="Loading sources…" />}
      {state.status === 'error' && (
        <ErrorState error={state.error} onRetry={() => void refresh()} />
      )}
      {state.status === 'ready' && state.sources.length === 0 && (
        <EmptyState>No sources configured yet.</EmptyState>
      )}
      {state.status === 'ready' && state.sources.length > 0 && (
        <Card padding="none">
          <div className="table-wrap">
            <table className="table">
              <caption>Configured sources</caption>
              <thead>
                <tr>
                  <th scope="col">Type</th>
                  <th scope="col">Name</th>
                  <th scope="col">Reference</th>
                  <th scope="col">Status</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {state.sources.map((source) => (
                  <tr key={source.id}>
                    <td>{source.type}</td>
                    <td>{source.name}</td>
                    <td>{renderReference(source.reference)}</td>
                    <td>
                      <StatusBadge status={source.enabled ? 'Enabled' : 'Disabled'} />
                    </td>
                    <td>
                      <div className="btn-group">
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          onClick={() => setFormState({ mode: 'edit', source })}
                          disabled={togglingId === source.id}
                          aria-label={`Edit source "${source.name}"`}
                        >
                          Edit
                        </Button>
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          onClick={() => void toggleEnabled(source)}
                          disabled={togglingId === source.id}
                          aria-label={`${source.enabled ? 'Disable' : 'Enable'} source "${source.name}"`}
                        >
                          {togglingId === source.id
                            ? 'Updating…'
                            : source.enabled
                              ? 'Disable'
                              : 'Enable'}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </section>
  );
}

function renderReference(reference?: string) {
  if (!reference) {
    return null;
  }
  try {
    const url = new URL(reference);
    return (
      <a href={url.toString()} target="_blank" rel="noreferrer">
        {reference}
      </a>
    );
  } catch {
    return reference;
  }
}
