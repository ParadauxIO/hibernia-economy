import { describe, expect, it } from 'vitest';
import { marketItemHref, marketReturnPath } from '@/lib/marketNavigation';

describe('market return navigation', () => {
  it('retains the catalogue query and page while dropping unrelated parameters', () => {
    expect(marketReturnPath('/chestshop?q=diamond&page=3&limit=25&next=https://example.org')).toBe('/chestshop?q=diamond&page=3&limit=25');
  });
  it('keeps shop direction, units, item and stock filters', () => {
    expect(marketReturnPath('/chestshop/shops?direction=sell&units=64&item=minecraft%3Adiamond&inStock=true')).toBe('/chestshop/shops?item=minecraft%3Adiamond&direction=sell&units=64&inStock=true');
  });
  it.each(['https://example.org', '//example.org', '/\\example.org', 'javascript:alert(1)', '/admin', '/chestshop/../admin', '/chestshop\n', undefined])('rejects an unsafe or unrelated destination: %s', value => {
    expect(marketReturnPath(value)).toBe('/chestshop');
  });
  it('encodes custom item keys and nested query values exactly once', () => {
    const href = marketItemHref('custom:ruby sword#02', '/chestshop?q=ruby+%26+gold&page=2', 'sell');
    const url = new URL(href, 'https://explorer.invalid');
    expect(url.pathname).toBe('/chestshop/items/custom%3Aruby%20sword%2302');
    expect(url.searchParams.get('from')).toBe('/chestshop?q=ruby+%26+gold&page=2');
    expect(url.searchParams.get('direction')).toBe('sell');
  });
});
