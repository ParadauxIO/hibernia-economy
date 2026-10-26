import { cx } from './cx';

const UUID = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;
const SIZES = [16, 24, 32, 48, 64, 96, 128] as const;

/** The smallest served size that covers `px` at 2× density. */
export function avatarSize(px: number): number {
  return SIZES.find((s) => s >= px * 2) ?? 128;
}

export interface PlayerHeadProps {
  uuid: string | null | undefined;
  /** Used for the fallback initial and, when `alt` is not given, nothing else: heads are decorative next to a name. */
  name?: string | null | undefined;
  size?: number | undefined;
  /** Alt text. Default "" (decorative) because a head is always shown beside the player's name. */
  alt?: string | undefined;
  /** The host's avatar proxy. Visitors never request the third-party service directly. */
  base?: string | undefined;
  className?: string | undefined;
}

/**
 * A player's Minecraft head, served through the host's `/api/avatar/[uuid]`
 * proxy (cached 24 h) so third parties never see visitor IPs.
 */
export function PlayerHead({ uuid, name, size = 20, alt = '', base = '/api/avatar', className }: PlayerHeadProps) {
  if (!uuid || !UUID.test(uuid)) {
    return <span className={cx('player-head', 'is-fallback', className)} style={{ width: size, height: size }} aria-hidden={alt ? undefined : 'true'} role={alt ? 'img' : undefined} aria-label={alt || undefined}>
      {name ? name.charAt(0).toUpperCase() : ''}
    </span>;
  }
  // eslint-disable-next-line @next/next/no-img-element -- a 20px proxied PNG; next/image adds nothing here
  return <img className={cx('player-head', className)} src={`${base}/${uuid.toLowerCase()}?size=${avatarSize(size)}`} width={size} height={size} alt={alt} loading="lazy" decoding="async" />;
}
