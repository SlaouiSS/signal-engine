import type { ReactNode } from 'react';

/**
 * A link to an original source outside the application. Renders nothing when `href` is missing or
 * not a valid absolute URL — never a broken link. Never proxies or fetches the target itself.
 */
export default function ExternalLink({ href, children }: { href?: string; children: ReactNode }) {
  if (!href || !isValidUrl(href)) {
    return null;
  }
  return (
    <a href={href} target="_blank" rel="noreferrer">
      {children}
    </a>
  );
}

function isValidUrl(value: string): boolean {
  try {
    new URL(value);
    return true;
  } catch {
    return false;
  }
}
