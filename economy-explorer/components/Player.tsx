import { EntityLink } from '@democracycraft/design-system/next';
import { playerHref, playerName, previewHref } from '@/lib/entityLinks';

interface PlayerProps {
  name?: string | null | undefined;
  uuid?: string | null | undefined;
  /** When false, render plain text (e.g. inside a row that's already a link). */
  link?: boolean;
}

/**
 * A player, shown with their head and linked to their profile (/players/<name>)
 * with a hover preview. Never a raw UUID: a player the directory hasn't named
 * yet shows a short UUID and links by UUID, which the profile resolves.
 */
export function Player({ name, uuid, link = true }: PlayerProps) {
  const realUuid = uuid ?? (name && /^[0-9a-f-]{36}$/i.test(name) ? name : null);
  if (!realUuid && !name) return <span className="muted">—</span>;
  const label = playerName(name, realUuid);
  if (!link) return <span title={realUuid ?? undefined}>{label}</span>;
  return <EntityLink kind="player" href={playerHref(name, realUuid)} name={label} uuid={realUuid}
    preview={realUuid ? previewHref('player', realUuid) : undefined} />;
}
