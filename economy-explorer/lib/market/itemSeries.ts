// Shapes item rollup days (explorer_rollup_item_day via services/rollup
// itemDays) into the item page's charts and figures. Pure, so it is unit-tested.
//
// Directions are as ChestShop records them: BUY is a customer buying from a
// shop (the price shops charge), SELL is a customer selling to a shop (the
// price shops pay). Unit prices are DECIMAL(19,4) strings; money totals stay
// exact (lib/money); chart coordinates are numbers.

import type { Candle } from '@/lib/derived';
import { divRound, parseMoney, sum, toDbString, toNumber } from '@/lib/money';

export interface ItemDayInput {
  itemKey: string;
  day: string;
  direction: 'BUY' | 'SELL';
  qty: number;
  volume: string;
  trades: number;
  minUnitPrice: string;
  maxUnitPrice: string;
  openUnitPrice: string;
  closeUnitPrice: string;
}

const num = (s: string): number => Number(s);

/** Daily OHLC candles for one item and direction, oldest first. */
export function dailyCandles(days: readonly ItemDayInput[], itemKey: string, direction: 'BUY' | 'SELL'): Candle[] {
  return days
    .filter((d) => d.itemKey === itemKey && d.direction === direction && d.trades > 0)
    .sort((a, b) => a.day.localeCompare(b.day))
    .map((d) => ({
      bucketStart: new Date(`${d.day}T00:00:00Z`),
      open: num(d.openUnitPrice),
      high: num(d.maxUnitPrice),
      low: num(d.minUnitPrice),
      close: num(d.closeUnitPrice),
      volume: d.qty,
    }));
}

/** Quantity-weighted average unit price as a chart coordinate, or null without quantity. */
function avg(volume: string, qty: number): number | null {
  return qty > 0 ? num(volume) / qty : null;
}

export type SpreadRow = { date: string; buy: number | null; sell: number | null; spread: number | null };

/**
 * Average shop price (BUY) and average buy-back price (SELL) per day, and the
 * spread between them: what a shop keeps on an item bought and sold back.
 */
export function spreadRows(days: readonly ItemDayInput[], itemKey: string): SpreadRow[] {
  const byDay = new Map<string, SpreadRow>();
  for (const d of days) {
    if (d.itemKey !== itemKey) continue;
    const row = byDay.get(d.day) ?? { date: d.day, buy: null, sell: null, spread: null };
    if (d.direction === 'BUY') row.buy = avg(d.volume, d.qty);
    else row.sell = avg(d.volume, d.qty);
    byDay.set(d.day, row);
  }
  return [...byDay.values()].sort((a, b) => a.date.localeCompare(b.date))
    .map((r) => ({ ...r, spread: r.buy !== null && r.sell !== null ? r.buy - r.sell : null }));
}

export type VolumeRow = { date: string; bought: number; sold: number };

/** Trades per day, split by direction: customers buying from shops, and selling to them. */
export function tradeRows(days: readonly ItemDayInput[], itemKey: string): VolumeRow[] {
  const byDay = new Map<string, VolumeRow>();
  for (const d of days) {
    if (d.itemKey !== itemKey) continue;
    const row = byDay.get(d.day) ?? { date: d.day, bought: 0, sold: 0 };
    if (d.direction === 'BUY') row.bought += d.trades;
    else row.sold += d.trades;
    byDay.set(d.day, row);
  }
  return [...byDay.values()].sort((a, b) => a.date.localeCompare(b.date));
}

/** Average price per day for several items (both directions), one column per item key. */
export function compareRows(days: readonly ItemDayInput[], itemKeys: readonly string[]): Record<string, string | number | null>[] {
  const acc = new Map<string, Map<string, { volume: bigint; qty: number }>>();
  for (const d of days) {
    if (!itemKeys.includes(d.itemKey)) continue;
    const day = acc.get(d.day) ?? new Map<string, { volume: bigint; qty: number }>();
    const cell = day.get(d.itemKey) ?? { volume: 0n, qty: 0 };
    cell.volume += parseMoney(d.volume);
    cell.qty += d.qty;
    day.set(d.itemKey, cell);
    acc.set(d.day, day);
  }
  return [...acc.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([date, cells]) => {
    const row: Record<string, string | number | null> = { date };
    for (const key of itemKeys) {
      const c = cells.get(key);
      row[key] = c && c.qty > 0 ? toNumber(c.volume) / c.qty : null;
    }
    return row;
  });
}

export interface ItemSummary {
  trades: number;
  qty: number;
  volume: string;
  /** Average unit price over the range, rounded to cents; null without trades. */
  avgPrice: string | null;
  /** Latest day's closing prices per direction (DECIMAL strings). */
  lastBuy: string | null;
  lastSell: string | null;
  /** First and last day's average price (chart coordinates), for the change figure. */
  firstAvg: number | null;
  lastAvg: number | null;
}

export function summarise(days: readonly ItemDayInput[], itemKey: string): ItemSummary {
  const own = days.filter((d) => d.itemKey === itemKey).sort((a, b) => a.day.localeCompare(b.day));
  const trades = own.reduce((n, d) => n + d.trades, 0);
  const qty = own.reduce((n, d) => n + d.qty, 0);
  const volume = sum(own.map((d) => d.volume));
  const lastOf = (dir: 'BUY' | 'SELL') => [...own].reverse().find((d) => d.direction === dir)?.closeUnitPrice ?? null;
  const daily = new Map<string, { volume: number; qty: number }>();
  for (const d of own) {
    const x = daily.get(d.day) ?? { volume: 0, qty: 0 };
    x.volume += num(d.volume);
    x.qty += d.qty;
    daily.set(d.day, x);
  }
  const series = [...daily.values()].filter((x) => x.qty > 0).map((x) => x.volume / x.qty);
  return {
    trades,
    qty,
    volume: toDbString(volume),
    avgPrice: qty > 0 ? toDbString(divRound(volume, qty)) : null,
    lastBuy: lastOf('BUY'),
    lastSell: lastOf('SELL'),
    firstAvg: series[0] ?? null,
    lastAvg: series[series.length - 1] ?? null,
  };
}
