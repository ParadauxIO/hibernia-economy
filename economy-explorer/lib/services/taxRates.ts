import 'server-only';
import { memo } from '@/lib/cache';
import { findConfigByPrefix } from '@/lib/sql/economyConfig';
import { parseTaxRates, type TaxRates } from '@/lib/taxRates';

// The rates change only on a plugin restart or /treasury reload, so a minute of
// staleness is invisible and keeps an anonymous docs endpoint off the DB.
const WINDOW_MS = 60_000;

/** Live tax rates for the public docs. Not per-viewer: everyone is charged the same. */
export function getTaxRates(): Promise<TaxRates> {
  return memo('docs:tax-rates', WINDOW_MS, async () => parseTaxRates(await findConfigByPrefix('tax.')));
}
