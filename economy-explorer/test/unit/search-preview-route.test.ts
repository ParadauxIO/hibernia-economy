import { NextRequest } from 'next/server';
import { describe, expect, it, vi } from 'vitest';

const suggest = vi.hoisted(() => vi.fn());
vi.mock('@/lib/services/search', () => ({ suggest }));
const { GET } = await import('@/app/api/search/preview/route');

describe('GET /api/search/preview', () => {
  it('returns searchPreview-shaped results plus items, with the limit clamped', async () => {
    suggest.mockResolvedValue([
      { kind: 'account', label: 'Acme', account_id: 3 },
      { kind: 'item', label: 'Diamond', item_key: 'DIAMOND' },
      { kind: 'player', label: 'Alice' },
    ]);
    const res = await GET(new NextRequest('http://x/api/search/preview?q=ac&limit=99'));
    expect(suggest).toHaveBeenCalledWith('ac', { limit: 8, kinds: ['account', 'firm', 'player', 'item'] });
    expect(await res.json()).toEqual({
      results: [{ kind: 'account', label: 'Acme', account_id: 3 }, { kind: 'player', label: 'Alice' }],
      items: [{ kind: 'item', label: 'Diamond', item_key: 'DIAMOND' }],
    });
  });

  it('defaults the limit and passes short queries through to the service rule', async () => {
    suggest.mockResolvedValue([]);
    await GET(new NextRequest('http://x/api/search/preview?q=a'));
    expect(suggest).toHaveBeenLastCalledWith('a', { limit: 4, kinds: ['account', 'firm', 'player', 'item'] });
  });
});
