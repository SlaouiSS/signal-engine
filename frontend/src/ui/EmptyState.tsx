import type { ReactNode } from 'react';

/**
 * A simple, non-alarming placeholder for "there is nothing here yet" — used instead of a bare
 * paragraph so every empty list/table in the application looks and reads the same way. Renders
 * `children` verbatim as the message, so existing copy is preserved exactly.
 */
export default function EmptyState({ children }: { children: ReactNode }) {
  return (
    <div className="empty-state">
      <div className="empty-state-icon" aria-hidden="true" />
      <p>{children}</p>
    </div>
  );
}
