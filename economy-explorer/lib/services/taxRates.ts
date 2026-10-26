import 'server-only';
import { memo } from '@/lib/cache';
import { findConfigByPrefix } from '@/lib/sql/economyConfig';
import { docTaxRates, taxConfigFromRows, type TaxConfig, type TaxRates } from '@/lib/taxRates';
import { fetchTaxConfig } from '@/lib/treasuryTaxRates';

// The one source of tax rates (spec §10.5) for both the taxation page and the
// docs: treasury-rest-api's /api/v1/config/tax, cached for 60 s. The
// economy_config rows Treasury publishes are read only when the endpoint can't
// be reached, so an outage of the REST service doesn't blank the docs. The
// endpoint publishes balance and source-income tax; corporate and ChestShop
// rates are not exposed by it, so nothing here invents them.
const WINDOW_MS = 60_000;

export interface SourcedTaxConfig extends TaxConfig {
  /** Where these figures came from. */
  source: 'api' | 'economy_config';
}

export function getTaxConfig(): Promise<SourcedTaxConfig> {
  return memo('tax-rates:v2', WINDOW_MS, async () => {
    const api = await fetchTaxConfig();
    if (api) return { ...api, source: 'api' as const };
    return { ...taxConfigFromRows(await findConfigByPrefix('tax.')), source: 'economy_config' as const };
  });
}

/** Live tax rates for the public docs. Not per-viewer: everyone is charged the same. */
export async function getTaxRates(): Promise<TaxRates> {
  return docTaxRates(await getTaxConfig());
}
