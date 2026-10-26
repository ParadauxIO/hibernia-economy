import { describe, expect, it } from 'vitest';
import { payCommand, payTemplate } from '@/lib/payCommand';
import { priceToCents, formatShopPrice } from '@/lib/shopPrice';
import { availability } from '@/lib/market/availability';
import { compareRows, dailyCandles, spreadRows, summarise, tradeRows, type ItemDayInput } from '@/lib/market/itemSeries';
import { toCsv } from '@/lib/entityExport';
import { playerHref, playerName, previewHref } from '@/lib/entityLinks';
import { accountTxnsTable } from '@/lib/tables/accountTxns';
import { playersTable } from '@/lib/tables/players';
import { shopsTable } from '@/lib/tables/shops';
import { parseTableState } from '@/lib/tables/core';

describe('payCommand', () => {
  const personal = { accountId: 7, accountType: 'PERSONAL', accountName: '00000000-0000-0000-0000-000000000001', ownerName: 'Ada', firm: null };

  it('fills the default template for each account type', () => {
    expect(payCommand(personal, {})).toBe('/pay Ada <amount>');
    expect(payCommand({ ...personal, accountType: 'GOVERNMENT', accountName: 'Treasury', ownerName: null }, {})).toBe('/pay Treasury <amount>');
    expect(payCommand({ ...personal, accountType: 'BUSINESS', accountName: 'Ops', firm: { id: 4, name: 'Acme' } }, {})).toBe('/firm account pay into Acme 7 <amount>');
  });

  it('uses the firm id when the name would split the command', () => {
    expect(payCommand({ ...personal, accountType: 'BUSINESS', firm: { id: 4, name: 'Riverside Builders' } }, {})).toBe('/firm account pay into 4 7 <amount>');
  });

  it('reads templates from the environment, and an empty one turns the action off', () => {
    expect(payCommand(personal, { PAY_COMMAND_PERSONAL: '/money send {player} {id}' })).toBe('/money send Ada 7');
    expect(payCommand(personal, { PAY_COMMAND_PERSONAL: '' })).toBeNull();
    expect(payTemplate('SYSTEM', {})).toBeNull();
  });

  it('gives no command when a placeholder can’t be filled', () => {
    expect(payCommand({ ...personal, ownerName: null }, {})).toBeNull();
    expect(payCommand({ ...personal, accountType: 'GOVERNMENT', accountName: 'Public Works' }, {})).toBeNull();
  });
});

describe('shop prices', () => {
  it('rounds a four-place unit price to cents exactly', () => {
    expect(priceToCents('1.2350')).toBe('1.24');
    expect(priceToCents('1.2349')).toBe('1.23');
    expect(priceToCents('0.0049')).toBe('0.00');
    expect(priceToCents('-2.005')).toBe('-2.01');
    expect(priceToCents('12')).toBe('12.00');
    expect(priceToCents('abc')).toBeNull();
  });

  it('formats with the given currency', () => {
    expect(formatShopPrice(0.0025, '£')).toBe('£0.0025');
  });
});

describe('availability', () => {
  const now = Date.parse('2026-10-26T12:00:00Z');
  const shop = { current_stock: 128, current_space: 32, stock_at: new Date(now - 3_600_000), batch_qty: 64 };
  it.each([
    [{}, 'buy', 'In stock', 'good'],
    [{}, 'sell', 'Limited space', 'warn'],
    [{ current_stock: 10 }, 'buy', 'Below one batch', 'warn'],
    [{ stock_at: new Date(now - 25 * 3_600_000) }, 'buy', 'Recheck', 'warn'],
    [{ current_stock: null }, 'buy', 'Unknown', 'neutral'],
    [{ stock_at: new Date(now + 60_000) }, 'buy', 'Unknown', 'neutral'],
    [{ admin_shop: 1 }, 'sell', 'Unlimited', 'good'],
  ] as const)('%o %s → %s', (over, direction, label, tone) => {
    expect(availability({ ...shop, ...over }, direction, now)).toMatchObject({ label, tone });
  });
});

describe('item series', () => {
  const day = (itemKey: string, d: string, direction: 'BUY' | 'SELL', qty: number, volume: string, open: string, close: string): ItemDayInput => ({
    itemKey, day: d, direction, qty, volume, trades: 1, minUnitPrice: open < close ? open : close, maxUnitPrice: open < close ? close : open, openUnitPrice: open, closeUnitPrice: close,
  });
  const days = [
    day('DIAMOND', '2026-10-02', 'BUY', 10, '50.00', '5.0000', '5.2000'),
    day('DIAMOND', '2026-10-01', 'BUY', 10, '40.00', '4.0000', '4.1000'),
    day('DIAMOND', '2026-10-01', 'SELL', 5, '15.00', '3.0000', '3.0000'),
    day('IRON', '2026-10-01', 'BUY', 4, '2.00', '0.5000', '0.5000'),
  ];

  it('builds daily candles for one side, oldest first', () => {
    expect(dailyCandles(days, 'DIAMOND', 'BUY').map((c) => [c.bucketStart.toISOString().slice(0, 10), c.open, c.close])).toEqual([['2026-10-01', 4, 4.1], ['2026-10-02', 5, 5.2]]);
  });

  it('pairs buy and sell averages into a spread', () => {
    expect(spreadRows(days, 'DIAMOND')).toEqual([
      { date: '2026-10-01', buy: 4, sell: 3, spread: 1 },
      { date: '2026-10-02', buy: 5, sell: null, spread: null },
    ]);
  });

  it('counts trades per side and compares items per day', () => {
    expect(tradeRows(days, 'DIAMOND')).toEqual([{ date: '2026-10-01', bought: 1, sold: 1 }, { date: '2026-10-02', bought: 1, sold: 0 }]);
    expect(compareRows(days, ['DIAMOND', 'IRON'])[0]).toEqual({ date: '2026-10-01', DIAMOND: 55 / 15, IRON: 0.5 });
  });

  it('summarises exactly, with the latest close per side', () => {
    expect(summarise(days, 'DIAMOND')).toMatchObject({ trades: 3, qty: 25, volume: '105.00', avgPrice: '4.20', lastBuy: '5.2000', lastSell: '3.0000' });
    expect(summarise([], 'DIAMOND')).toMatchObject({ trades: 0, avgPrice: null, lastBuy: null, firstAvg: null });
  });
});

describe('entity links and exports', () => {
  it('links players by name, or by UUID while they have none', () => {
    expect(playerHref('Ada_Lovelace')).toBe('/players/Ada_Lovelace');
    expect(playerHref('.Bedrock Bob')).toBe('/players/.Bedrock%20Bob');
    expect(playerHref(null, '00000000-0000-0000-0000-00000000a1ce')).toBe('/players/00000000-0000-0000-0000-00000000a1ce');
    expect(playerHref('0000000000000000000000000000A1CE', 'u')).toBe('/players/u');
    expect(playerName(null, '00000000-0000-0000-0000-00000000a1ce')).toBe('00000000…');
    expect(previewHref('item', 'nexo:ruby#02')).toBe('/api/preview/item/nexo%3Aruby%2302');
  });

  it('writes RFC 4180 CSV and defuses spreadsheet formulas', () => {
    expect(toCsv([{ a: 'x,y', b: '-5.00', c: '=SUM(A1)', d: 'say "hi"' }])).toBe('a,b,c,d\r\n"x,y",-5.00,\'=SUM(A1),"say ""hi"""\r\n');
    expect(toCsv([], ['a'])).toBe('a\r\n');
  });
});

describe('entity tables', () => {
  it('drop history filters that are not declared', () => {
    const s = parseTableState(new URLSearchParams('f=category:in:wages&f=direction:in:internal&f=plugin:in:x&sort=amount'), accountTxnsTable);
    expect(s.filters).toEqual([{ field: 'category', op: 'in', values: ['wages'] }]);
    expect(s.sort).toEqual({ field: 'date', dir: 'desc' });
    expect(parseTableState(new URLSearchParams('f=category:in:internal'), accountTxnsTable).filters).toEqual([]);
  });

  it('keep the old shop links working', () => {
    const s = parseTableState(new URLSearchParams('item=DIAMOND&direction=sell'), shopsTable);
    expect(s.filters).toEqual([{ field: 'item', op: 'in', values: ['DIAMOND'] }, { field: 'direction', op: 'in', values: ['sell'] }]);
  });

  it('default the player directory to recently seen', () => {
    expect(parseTableState(new URLSearchParams(), playersTable).sort).toEqual({ field: 'lastSeen', dir: 'desc' });
  });
});
