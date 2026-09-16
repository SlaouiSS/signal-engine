import type { ReactNode } from 'react';

export interface PageHeaderProps {
  /** `id` the page's `<section aria-labelledby>` already points at — preserved, not changed. */
  id: string;
  title: string;
  description?: string;
  /** Primary page-level actions, e.g. "Add source". */
  actions?: ReactNode;
}

/** The consistent page title block: a heading, optional description, and optional actions. */
export default function PageHeader({ id, title, description, actions }: PageHeaderProps) {
  return (
    <div className="page-header">
      <div>
        <h2 id={id}>{title}</h2>
        {description && <p className="page-header-description">{description}</p>}
      </div>
      {actions && <div className="page-header-actions">{actions}</div>}
    </div>
  );
}
