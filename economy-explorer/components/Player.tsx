import Link from 'next/link';
import type { Route } from 'next';
import { shortenUuid, looksLikeUuid } from '@/lib/format';

interface PlayerProps {
  name?: string | null | undefined;
  uuid?: string | null | undefined;
  /** When false, render plain text (e.g. inside a row that's already a link). */
  link?: boolean;
}

/**
 * Render a player by name (UUID in the tooltip), clickable → /search?q=<name>
 * so you can hop to their accounts / firm memberships. Falls back to a short
 * UUID when only the UUID is known, and an em-dash when neither is.
 */
export function Player({ name, uuid, link = true }: PlayerProps) {
  // A UUID-shaped name is junk (personal accounts default display_name to their
  // UUID) — treat it as "no name" and fall through to the short-UUID branch.
  const realName = name && !looksLikeUuid(name) ? name : null;
  const realUuid = uuid ?? (name && looksLikeUuid(name) ? name : null);

  if (!realUuid && !realName) return <span style={{ color: 'var(--fg-muted)' }}>—</span>;
  if (realName) {
    if (!link) return <span title={realUuid ?? undefined} style={{ color: 'var(--fg)' }}>{realName}</span>;
    return (
      <Link
        href={`/search?q=${encodeURIComponent(realName)}` as Route}
        title={realUuid ?? undefined}
        className="player-link"
        prefetch={false}
      >
        {realName}
      </Link>
    );
  }
  return (
    <span className="mono" title={realUuid ?? undefined} style={{ color: 'var(--fg-soft)' }}>
      {shortenUuid(realUuid)}
    </span>
  );
}
