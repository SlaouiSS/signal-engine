import { useState, type FormEvent } from 'react';

import { api, type SourceResponse } from '../api';
import Button from '../ui/Button';
import ErrorMessage from '../ui/ErrorMessage';
import TextField from '../ui/TextField';
import type { Source } from './useSources';

interface SourceFormProps {
  /** The source being edited, or `undefined` to create a new one. */
  source?: Source;
  onCancel: () => void;
  onSuccess: (source: SourceResponse) => void;
}

/**
 * Create/edit form for a source (docs/02-functional-spec.md Section 4.2). `type`, `name`, and
 * `reference` are all required — mirroring the backend's `@NotBlank` validation
 * (backend/.../interfaces/rest/source/SourceConfigurationRequest.java) — but the backend defines no
 * fixed vocabulary for `type` (docs/02-functional-spec.md Section 4.1), so it is a plain text field.
 */
export default function SourceForm({ source, onCancel, onSuccess }: SourceFormProps) {
  const [type, setType] = useState(source?.type ?? '');
  const [name, setName] = useState(source?.name ?? '');
  const [reference, setReference] = useState(source?.reference ?? '');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  const isEditing = source !== undefined;

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (submitting) {
      return; // guards against a duplicate submission from a fast repeated click/Enter
    }

    const trimmedType = type.trim();
    const trimmedName = name.trim();
    const trimmedReference = reference.trim();
    if (!trimmedType || !trimmedName || !trimmedReference) {
      setFieldError('Type, name, and reference are all required.');
      return;
    }
    setFieldError(null);
    setSubmitError(null);
    setSubmitting(true);

    const configuration = { type: trimmedType, name: trimmedName, reference: trimmedReference };
    try {
      const result = isEditing
        ? await api.updateSource(source.id, configuration)
        : await api.createSource(configuration);
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
      aria-label={isEditing ? 'Edit source' : 'Add source'}
    >
      <h3 className="form-title">{isEditing ? 'Edit source' : 'Add source'}</h3>

      <TextField
        id="source-type"
        label="Type"
        value={type}
        onChange={(event) => setType(event.target.value)}
        disabled={submitting}
      />

      <TextField
        id="source-name"
        label="Name"
        value={name}
        onChange={(event) => setName(event.target.value)}
        disabled={submitting}
      />

      <TextField
        id="source-reference"
        label="Reference"
        value={reference}
        onChange={(event) => setReference(event.target.value)}
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
          {submitting ? 'Saving…' : isEditing ? 'Save changes' : 'Add source'}
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel} disabled={submitting}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
