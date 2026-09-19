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
import InterestForm from './InterestForm';
import { useAreasOfInterest, type AreaOfInterest } from './useAreasOfInterest';
import { useInterests, type Interest } from './useInterests';

type FormState =
  | { mode: 'closed' }
  | { mode: 'create'; area: AreaOfInterest }
  | { mode: 'edit'; area: AreaOfInterest; interest: Interest };

/**
 * Configure interests within the six fixed areas of interest (docs/02-functional-spec.md Section 5,
 * workflow W2). Interests are enabled/disabled rather than removed — the backend has no delete
 * operation for an interest (see `ManageInterestsUseCase`'s documented open question).
 */
export default function InterestsPage() {
  const { state: areasState, refresh: refreshAreas } = useAreasOfInterest();
  const { state: interestsState, refresh: refreshInterests } = useInterests();
  const [formState, setFormState] = useState<FormState>({ mode: 'closed' });
  const [togglingId, setTogglingId] = useState<string | null>(null);
  const [toggleError, setToggleError] = useState<unknown>(null);

  function closeForm() {
    setFormState({ mode: 'closed' });
  }

  function handleFormSuccess() {
    closeForm();
    void refreshInterests();
  }

  async function toggleEnabled(interest: Interest) {
    setToggleError(null);
    setTogglingId(interest.id);
    try {
      if (interest.enabled) {
        await api.disableInterest(interest.id);
      } else {
        await api.enableInterest(interest.id);
      }
      await refreshInterests();
    } catch (error) {
      setToggleError(error);
    } finally {
      setTogglingId(null);
    }
  }

  if (areasState.status === 'loading' || interestsState.status === 'loading') {
    return (
      <section aria-labelledby="interests-heading" className="page">
        <PageHeader id="interests-heading" title="Interests" />
        <LoadingState label="Loading interests…" />
      </section>
    );
  }

  if (areasState.status === 'error') {
    return (
      <section aria-labelledby="interests-heading" className="page">
        <PageHeader id="interests-heading" title="Interests" />
        <ErrorState error={areasState.error} onRetry={() => void refreshAreas()} />
      </section>
    );
  }

  if (interestsState.status === 'error') {
    return (
      <section aria-labelledby="interests-heading" className="page">
        <PageHeader id="interests-heading" title="Interests" />
        <ErrorState error={interestsState.error} onRetry={() => void refreshInterests()} />
      </section>
    );
  }

  const areas = areasState.areas;
  const interests = interestsState.interests;

  return (
    <section aria-labelledby="interests-heading" className="page">
      <PageHeader
        id="interests-heading"
        title="Interests"
        description="What Signal Engine watches for within each area of interest."
      />
      <PageInfoPanel
        whatItIs="Interests are the topics you care about, grouped under fixed areas of interest."
        whyItExists="Signal Engine compares collected information with your enabled interests when it assesses relevance."
        whatToExpect="Add, edit, enable or disable interests within each area."
      />
      <ErrorMessage error={toggleError} />

      {areas.map((area) => {
        const areaInterests = interests.filter((i) => i.areaOfInterestCode === area.code);
        const isFormOpenHere = formState.mode !== 'closed' && formState.area.code === area.code;

        return (
          <Card key={area.code} aria-labelledby={`area-${area.code}-heading`}>
            <h3 id={`area-${area.code}-heading`} className="section-title">
              {area.name}
            </h3>

            {areaInterests.length === 0 && (
              <EmptyState>No interests configured yet in this area.</EmptyState>
            )}
            {areaInterests.length > 0 && (
              <ul className="stack-list">
                {areaInterests.map((interest) => (
                  <li key={interest.id}>
                    <div className="item-row">
                      <div className="item-row-primary">
                        <span>{interest.description}</span>
                        <StatusBadge status={interest.enabled ? 'Enabled' : 'Disabled'} />
                      </div>
                      <div className="btn-group">
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          onClick={() => setFormState({ mode: 'edit', area, interest })}
                          disabled={togglingId === interest.id}
                          aria-label={`Edit interest "${interest.description}"`}
                        >
                          Edit
                        </Button>
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          onClick={() => void toggleEnabled(interest)}
                          disabled={togglingId === interest.id}
                          aria-label={`${interest.enabled ? 'Disable' : 'Enable'} interest "${interest.description}"`}
                        >
                          {togglingId === interest.id
                            ? 'Updating…'
                            : interest.enabled
                              ? 'Disable'
                              : 'Enable'}
                        </Button>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}

            {!isFormOpenHere && (
              <div className={areaInterests.length > 0 ? 'card-section' : undefined}>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => setFormState({ mode: 'create', area })}
                >
                  Add interest
                </Button>
              </div>
            )}
            {isFormOpenHere && formState.mode === 'create' && (
              <div className="card-section">
                <InterestForm
                  areaCode={formState.area.code}
                  areaName={formState.area.name}
                  onCancel={closeForm}
                  onSuccess={handleFormSuccess}
                />
              </div>
            )}
            {isFormOpenHere && formState.mode === 'edit' && (
              <div className="card-section">
                <InterestForm
                  areaCode={formState.area.code}
                  areaName={formState.area.name}
                  interest={formState.interest}
                  onCancel={closeForm}
                  onSuccess={handleFormSuccess}
                />
              </div>
            )}
          </Card>
        );
      })}
    </section>
  );
}
