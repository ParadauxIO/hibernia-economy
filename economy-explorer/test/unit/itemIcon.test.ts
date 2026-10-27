import { describe, it, expect, afterEach, vi } from 'vitest';
import { itemIconUrl } from '@/lib/itemIcon';

afterEach(() => vi.unstubAllEnvs());

describe('itemIconUrl', () => {
  it('maps a Bukkit material to a lowercased texture url', () => {
    expect(itemIconUrl('DIAMOND_SWORD')).toBe('https://static.minecraftitemids.com/64/diamond_sword.png');
    expect(itemIconUrl('GRASS_BLOCK')).toBe('https://static.minecraftitemids.com/64/grass_block.png');
  });

  it('returns null for custom items', () => {
    expect(itemIconUrl('DIAMOND', 1)).toBeNull();
  });

  it('returns null for missing or non-alphanumeric materials', () => {
    expect(itemIconUrl(null)).toBeNull();
    expect(itemIconUrl(undefined)).toBeNull();
    expect(itemIconUrl('weird:name')).toBeNull();
    expect(itemIconUrl('has space')).toBeNull();
  });

  it('honours the ITEM_ICON_BASE override and normalises the trailing slash', () => {
    vi.stubEnv('ITEM_ICON_BASE', 'https://cdn.example/sprites'); // no trailing slash
    expect(itemIconUrl('STONE')).toBe('https://cdn.example/sprites/stone.png');
  });
  it.each(['http://cdn.example/icons', '//cdn.example/icons', 'https://user:pass@cdn.example/icons', 'javascript:alert(1)', 'https://cdn.example/icons?x=1', 'https://cdn.example/icons#x'])('falls back for an unsafe mirror (%s)', (base) => {
    vi.stubEnv('ITEM_ICON_BASE', base);
    expect(itemIconUrl('STONE')).toBe('https://static.minecraftitemids.com/64/stone.png');
  });

});
