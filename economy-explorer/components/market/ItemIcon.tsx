'use client';
// Minecraft item texture with a graceful fallback (lettered tile) when the
// icon is missing or fails to load. Reused on cards, tables, and the item
// detail header.

import { createContext, useContext, useState, type ReactNode } from 'react';

const IconOrigin = createContext('https://static.minecraftitemids.com');

/** The server supplies the runtime mirror origin, so deployments need no rebuild. */
export function ItemIconProvider({ origin, children }: { origin: string; children: ReactNode }) {
  return <IconOrigin.Provider value={origin}>{children}</IconOrigin.Provider>;
}

function safeIconUrl(icon: string | null, origin: string): string | null {
  if (!icon || /[\\\u0000-\u0020\u007f]/.test(icon)) return null;
  if (icon.startsWith('/') && !icon.startsWith('//')) return icon;
  try {
    const url = new URL(icon);
    return url.protocol === 'https:' && url.origin === origin && !url.username && !url.password ? url.href : null;
  } catch {
    return null;
  }
}

export function ItemIcon({ icon, name, size = 36 }: { icon: string | null; name: string; size?: number }) {
  // Remember WHICH icon failed, not just that one did: React reuses the instance
  // across list re-renders, so a 404 must not stick to a different item.
  const [failedIcon, setFailedIcon] = useState<string | null>(null);
  const safeIcon = safeIconUrl(icon, useContext(IconOrigin));
  const failed = failedIcon !== null && failedIcon === safeIcon;
  const dim = { width: size, height: size };
  if (!safeIcon || failed) {
    return (
      <span className="item-icon item-icon-fallback" style={{ ...dim, fontSize: Math.round(size * 0.42) }} aria-hidden>
        {(name || '?').slice(0, 1).toUpperCase()}
      </span>
    );
  }
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img className="item-icon" src={safeIcon} alt="" style={dim} loading="lazy" onError={() => setFailedIcon(safeIcon)} />
  );
}
