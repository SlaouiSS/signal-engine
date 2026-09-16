import type { ButtonHTMLAttributes } from 'react';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'md' | 'sm';
}

/**
 * A real `<button>` styled to match the visual system — never a styled `<div>` or `<a>` standing in
 * for one. Forwards every native button attribute unchanged (`type`, `disabled`, `aria-*`,
 * `onClick`), so it is a drop-in replacement for a plain `<button>` and does not change any
 * existing behavior or accessible name.
 */
export default function Button({
  variant = 'secondary',
  size = 'md',
  className,
  ...props
}: ButtonProps) {
  const classes = ['btn', `btn-${variant}`, size === 'sm' ? 'btn-sm' : null, className]
    .filter(Boolean)
    .join(' ');
  return <button className={classes} {...props} />;
}
