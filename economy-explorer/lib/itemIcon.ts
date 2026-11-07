// Resolves a Minecraft material name (Bukkit enum, e.g. "DIAMOND_SWORD",
// "GRASS_BLOCK") to an item-texture URL. Bukkit names lowercased map to
// vanilla namespaced ids, which the icon host serves as <id>.png.
//
// Base host is configurable via ITEM_ICON_BASE (e.g. to point at a self-hosted
// sprite mirror); defaults to the public minecraftitemids 64px renders, which
// cover both items and isometric block renders. Custom/unmapped items return
// null and the UI shows a neutral fallback.
export const DEFAULT_BASE = 'https://static.minecraftitemids.com/64/';

/** Only HTTPS, credential-free mirrors are trusted; bad config uses the default. */
export function itemIconBase(): URL {
  try {
    const base = new URL(process.env.ITEM_ICON_BASE || DEFAULT_BASE);
    if (base.protocol === 'https:' && !base.username && !base.password && !base.search && !base.hash) {
      base.pathname = base.pathname.replace(/\/?$/, '/');
      return base;
    }
  } catch { /* Fall back to the public mirror. */ }
  return new URL(DEFAULT_BASE);
}

export function itemIconUrl(material: string | null | undefined, itemCustom?: number): string | null {
  if (!material || itemCustom) return null;
  const id = material.toLowerCase().trim();
  if (!/^[a-z0-9_]+$/.test(id)) return null;
  const base = itemIconBase().href;
  return `${base}${id}.png`;
}
