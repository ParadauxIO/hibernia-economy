/** Keep return navigation inside the public market, with only known browse state. */
export function marketReturnPath(value: string | undefined): string {
  if (!value || value.length > 2048 || !value.startsWith('/') || value.startsWith('//') || /[\\\u0000-\u001f]/.test(value)) return '/chestshop';
  const url = new URL(value, 'https://explorer.invalid');
  const allowed: Record<string, string[]> = {
    '/market': ['days'],
    '/chestshop': ['q', 'page', 'limit'],
    '/chestshop/shops': ['q', 'item', 'direction', 'units', 'inStock', 'page', 'limit'],
  };
  const keys = allowed[url.pathname];
  if (url.origin !== 'https://explorer.invalid' || !keys) return '/chestshop';
  const params = new URLSearchParams();
  for (const key of keys) {
    const entry = url.searchParams.get(key);
    if (entry) params.set(key, entry);
  }
  return `${url.pathname}${params.size ? `?${params}` : ''}`;
}

export function marketItemHref(itemKey: string, returnTo?: string, direction?: 'buy' | 'sell'): string {
  const params = new URLSearchParams();
  if (returnTo) params.set('from', marketReturnPath(returnTo));
  if (direction) params.set('direction', direction);
  return `/chestshop/items/${encodeURIComponent(itemKey)}${params.size ? `?${params}` : ''}`;
}
