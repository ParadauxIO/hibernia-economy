import { beforeEach, expect, it, vi } from 'vitest';
const { getViewer, memo } = vi.hoisted(() => ({ getViewer: vi.fn(), memo: vi.fn() }));
vi.mock('@/lib/auth/viewer', () => ({ getViewer }));
vi.mock('@/lib/cache', () => ({ memo }));
import { requireLinkedViewer } from '@/lib/auth/linked';
import { getItemPage, getItemOffers, getShopDirectory } from '@/lib/services/market';
import { getPreview } from '@/lib/services/preview';
import { GET } from '@/app/api/preview/[kind]/[id]/route';
import { parseTableState } from '@/lib/tables/core';
import { shopsTable } from '@/lib/tables/shops';
beforeEach(() => { vi.clearAllMocks(); getViewer.mockResolvedValue({ anon: true }); });
it('preserves comparisons and repeated filters in the return URL', async () => {
  try {
    await requireLinkedViewer('/chestshop/items/DIAMOND', { compare: 'IRON_INGOT', f: ['a', 'b'] });
    throw new Error('anonymous read allowed');
  } catch (error) {
    expect((error as { digest: string }).digest).toContain('/api/auth/signin?callbackUrl=%2Fchestshop%2Fitems%2FDIAMOND%3Fcompare%3DIRON_INGOT%26f%3Da%26f%3Db');
  }
});
it.each([
  ['item history', () => getItemPage('DIAMOND', ['IRON_INGOT'], { from: '2026-10-01', to: '2026-10-28' })],
  ['offers', () => getItemOffers('DIAMOND')],
  ['shop directory', () => getShopDirectory(parseTableState({}, shopsTable))],
  ['item preview', () => getPreview('item', 'DIAMOND')],
] as const)('rejects anonymous %s before shared caches or database work', async (_label, read) => {
  await expect(read()).rejects.toThrow('NEXT_REDIRECT');
  expect(memo).not.toHaveBeenCalled();
});
it('allows ordinary signed-in viewers to read warm item caches', async () => {
  getViewer.mockResolvedValue({ anon: false, linked: true, minecraftUuid: '00000000-0000-0000-0000-00000000a1ce', keycloakSub: 'player' });
  memo.mockResolvedValue({ days: ['cached'] });
  await expect(getItemPage('DIAMOND', [], { from: '2026-10-01', to: '2026-10-28' })).resolves.toEqual({ days: ['cached'] });
  expect(memo).toHaveBeenCalledOnce();
});
it('returns uncacheable 401 for anonymous item preview API requests', async () => {
  const response = await GET(new Request('http://localhost/api/preview/item/DIAMOND'), { params: Promise.resolve({ kind: 'item', id: 'DIAMOND' }) });
  expect(response.status).toBe(401);
  expect(await response.json()).toEqual({ error: 'unauthorized' });
  expect(response.headers.get('cache-control')).toBe('private, no-store');
  expect(memo).not.toHaveBeenCalled();
});
it('never publicly caches signed-in item previews', async () => {
  getViewer.mockResolvedValue({ anon: false, linked: true, minecraftUuid: '00000000-0000-0000-0000-00000000a1ce', keycloakSub: 'player' });
  memo.mockResolvedValue({ title: 'Diamond' });
  const response = await GET(new Request('http://localhost/api/preview/item/DIAMOND'), { params: Promise.resolve({ kind: 'item', id: 'DIAMOND' }) });
  expect(response.status).toBe(200);
  expect(response.headers.get('cache-control')).toBe('private, no-store');
});
it('protects other entity previews too', async () => {
  const response = await GET(new Request('http://localhost/api/preview/player/Alice'), { params: Promise.resolve({ kind: 'player', id: 'Alice' }) });
  expect(response.status).toBe(401);
  expect(memo).not.toHaveBeenCalled();
});
it('denies signed-in but unlinked users before returning data', async () => {
  getViewer.mockResolvedValue({ anon: false, linked: false, minecraftUuid: null, keycloakSub: 'unlinked' });
  await expect(getItemOffers('DIAMOND')).rejects.toThrow('NEXT_REDIRECT');
  expect(memo).not.toHaveBeenCalled();
  const response = await GET(new Request('http://localhost/api/preview/item/DIAMOND'), { params: Promise.resolve({ kind: 'item', id: 'DIAMOND' }) });
  expect(response.status).toBe(403);
});
it('fails closed when viewer has a claim but no verified durable link', async () => {
  getViewer.mockResolvedValue({ anon: false, linked: false, minecraftUuid: '00000000-0000-0000-0000-00000000a1ce', keycloakSub: 'degraded' });
  await expect(getItemOffers('DIAMOND')).rejects.toThrow('NEXT_REDIRECT');
  expect(memo).not.toHaveBeenCalled();
});
