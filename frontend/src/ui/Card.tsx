import type { ElementType, HTMLAttributes } from 'react';

export interface CardProps extends HTMLAttributes<HTMLElement> {
  /** `'none'` for a card wrapping its own padded content, such as a table. */
  padding?: 'md' | 'none';
  /** The element to render as — `'section'` when the card is a distinct content region with its
   * own heading (matches what a bare `<section>` would have been), `'div'` (default) otherwise. */
  as?: ElementType;
}

/**
 * A surface: the visual container for a grouped chunk of page content (a table, a form, one
 * area's interests, a detail view). Forwards all element attributes — including `aria-labelledby`
 * and `id` — so it can replace a plain grouping `<div>`/`<section>` without changing the page's
 * structure or semantics.
 */
export default function Card({
  padding = 'md',
  as: Element = 'div',
  className,
  ...props
}: CardProps) {
  const classes = ['card', padding === 'none' ? 'card--flush' : null, className]
    .filter(Boolean)
    .join(' ');
  return <Element className={classes} {...props} />;
}
