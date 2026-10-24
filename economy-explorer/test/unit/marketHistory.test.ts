import { beforeEach, afterEach, expect, it, vi } from 'vitest';

const queries = vi.hoisted(() => ({
  findItemIdentity: vi.fn(), countItemSales: vi.fn(), listItemSellers: vi.fn(),
  countItemSellers: vi.fn(), itemPriceByDay: vi.fn(),
}));
vi.mock('@/lib/sql/market', () => queries);
vi.mock('@/lib/redis', () => ({ redis: () => null, tenantKey: (key: string) => key }));

beforeEach(() => {
  vi.resetModules();
  vi.resetAllMocks();
  queries.findItemIdentity.mockImplementation(async (key: string) => ({ item_name: key, material: key, item_custom: 0 }));
  queries.countItemSales.mockResolvedValue(12);
  queries.listItemSellers.mockResolvedValue([]);
  queries.countItemSellers.mockResolvedValue(0);
  queries.itemPriceByDay.mockResolvedValue([]);
});
afterEach(() => vi.useRealTimers());

it('shares one history calculation across concurrent and repeat item visits', async () => {
  const { getItemHistory } = await import('@/lib/services/market');
  const [first, second] = await Promise.all([getItemHistory('DIAMOND'), getItemHistory('DIAMOND')]);
  expect(first.totalSales).toBe(12);
  expect(second).toEqual(first);
  expect(await getItemHistory('DIAMOND')).toEqual(first);
  for (const query of Object.values(queries)) expect(query).toHaveBeenCalledTimes(1);
});

it('keeps different items separate', async () => {
  const { getItemHistory } = await import('@/lib/services/market');
  expect((await getItemHistory('DIAMOND')).identity?.item_name).toBe('DIAMOND');
  expect((await getItemHistory('IRON_INGOT')).identity?.item_name).toBe('IRON_INGOT');
  expect(queries.countItemSales).toHaveBeenCalledTimes(2);
});

it('serves the previous history while refreshing the next minute', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-20T00:00:00Z'));
  const { getItemHistory } = await import('@/lib/services/market');
  expect((await getItemHistory('DIAMOND')).totalSales).toBe(12);
  queries.countItemSales.mockResolvedValue(13);
  vi.setSystemTime(new Date('2026-10-20T00:01:00Z'));
  expect((await getItemHistory('DIAMOND')).totalSales).toBe(12);
  await vi.waitFor(async () => expect((await getItemHistory('DIAMOND')).totalSales).toBe(13));
  expect(queries.countItemSales).toHaveBeenCalledTimes(2);
});
