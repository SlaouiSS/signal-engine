import type { InputHTMLAttributes } from 'react';

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  id: string;
  label: string;
}

/**
 * A labeled text input: the `<label htmlFor>` / `<input id>` pairing every form field needs for
 * accessibility, styled consistently. Forwards every native input attribute (`value`, `onChange`,
 * `disabled`, `type`, …) unchanged, so it is a drop-in replacement for a raw labeled `<input>`.
 */
export default function TextField({ id, label, ...props }: TextFieldProps) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} {...props} />
    </div>
  );
}
