import type { Route } from 'next';
import Link from 'next/link';
import type { ReactNode } from 'react';
import { cx } from '../cx';
import { EntityPreview } from '../EntityPreview';
import { PlayerHead } from '../PlayerHead';

export type EntityKind = 'player' | 'account' | 'firm' | 'item';

export interface EntityLinkProps<T extends string = string> {
  kind: EntityKind;
  href: Route<T>;
  name: ReactNode;
  /** Player: their head is shown before the name. */
  uuid?: string | null | undefined;
  /** Account: a type dot (PERSONAL, BUSINESS, GOVERNMENT, SYSTEM) before the name. */
  accountType?: string | null | undefined;
  /** Account or firm id, shown muted after the name ("#1204"). */
  id?: string | number | null | undefined;
  /** Item: icon URL. */
  icon?: string | null | undefined;
  /**
   * URL of a JSON endpoint returning EntityPreviewData. When set, hovering or
   * focusing the link shows a preview card, fetched once on first open.
   */
  preview?: string | undefined;
  className?: string | undefined;
}

/** A link to a player, account, firm or item, with one optional lazy hover card per entity type. */
export function EntityLink<T extends string>({ kind, href, name, uuid, accountType, id, icon, preview, className }: EntityLinkProps<T>) {
  const link = <Link href={href} prefetch={false} className={cx('entity-link', `entity-${kind}`, className)}>
    {kind === 'player' && <PlayerHead uuid={uuid} name={typeof name === 'string' ? name : undefined} size={18} />}
    {kind === 'account' && accountType && <span className="entity-dot" data-type={accountType} aria-hidden="true" />}
    {/* eslint-disable-next-line @next/next/no-img-element -- item textures are tiny pixel art */}
    {kind === 'item' && icon && <img className="entity-icon" src={icon} width={18} height={18} alt="" loading="lazy" />}
    <span className="entity-name">{name}</span>
    {id != null && id !== '' && <span className="entity-id">#{id}</span>}
  </Link>;
  return preview ? <EntityPreview src={preview}>{link}</EntityPreview> : link;
}
