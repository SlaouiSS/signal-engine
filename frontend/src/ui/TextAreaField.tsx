import type { TextareaHTMLAttributes } from 'react';

export interface TextAreaFieldProps extends Omit<
  TextareaHTMLAttributes<HTMLTextAreaElement>,
  'id'
> {
  id: string;
  label: string;
}

/** The `<textarea>` counterpart to `TextField` — same labeling and styling contract. */
export default function TextAreaField({ id, label, ...props }: TextAreaFieldProps) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <textarea id={id} {...props} />
    </div>
  );
}
