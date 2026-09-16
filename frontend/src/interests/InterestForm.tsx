import { useState, type FormEvent } from 'react';

import { api, type InterestResponse } from '../api';
import Button from '../ui/Button';
import ErrorMessage from '../ui/ErrorMessage';
import TextAreaField from '../ui/TextAreaField';
import type { Interest } from './useInterests';

interface InterestFormProps {
  /** The area to add an interest under (create mode) — fixed, not user-selectable here (the user
   * chose the area by opening this form from that area's section). */
  areaCode: string;
  areaName: string;
  /** The interest being edited, or `undefined` to create a new one. */
  interest?: Interest;
  onCancel: () => void;
  onSuccess: (interest: InterestResponse) => void;
}

/**
 * Create/edit form for an interest (docs/02-functional-spec.md Section 5.2). Only the description
 * is editable after creation — the backend has no endpoint to move an interest to a different area
 * (`UpdateInterestDescriptionRequest` carries only `description`), so the area is shown as read-only
 * context in both modes.
 */
export default function InterestForm({
  areaCode,
  areaName,
  interest,
  onCancel,
  onSuccess,
}: InterestFormProps) {
  const [description, setDescription] = useState(interest?.description ?? '');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  const isEditing = interest !== undefined;

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (submitting) {
      return; // guards against a duplicate submission from a fast repeated click/Enter
    }

    const trimmedDescription = description.trim();
    if (!trimmedDescription) {
      setFieldError('Description is required.');
      return;
    }
    setFieldError(null);
    setSubmitError(null);
    setSubmitting(true);

    try {
      const result = isEditing
        ? await api.updateInterest(interest.id, { description: trimmedDescription })
        : await api.createInterest({
            areaOfInterestCode: areaCode,
            description: trimmedDescription,
          });
      onSuccess(result);
    } catch (error) {
      setSubmitError(error);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form
      className="form"
      onSubmit={(event) => void handleSubmit(event)}
      aria-label={isEditing ? 'Edit interest' : `Add interest in ${areaName}`}
    >
      <h4 className="form-title">{isEditing ? 'Edit interest' : `Add interest in ${areaName}`}</h4>
      <p className="text-secondary">
        Area: <strong>{areaName}</strong>
      </p>

      <TextAreaField
        id={`interest-description-${areaCode}`}
        label="Description"
        value={description}
        onChange={(event) => setDescription(event.target.value)}
        disabled={submitting}
      />

      {fieldError && (
        <p role="alert" className="alert">
          {fieldError}
        </p>
      )}
      <ErrorMessage error={submitError} />

      <div className="btn-group">
        <Button type="submit" variant="primary" disabled={submitting}>
          {submitting ? 'Saving…' : isEditing ? 'Save changes' : 'Add interest'}
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel} disabled={submitting}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
