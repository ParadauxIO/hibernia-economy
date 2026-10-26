import { fmtAmtFull } from '@/lib/format';
import { compare, tryParseMoney } from '@/lib/money';

// Tax rates have one source (spec §10.5): treasury-rest-api's public
// GET /api/v1/config/tax (lib/treasuryTaxRates.ts), cached for 60 s in
// lib/services/taxRates.ts. The economy_config rows Treasury publishes
// (PAR-80) are the fallback only, read when the endpoint can't be reached, and
// are shaped here into the same contract so the two never disagree in format.
// Pure, so the encoding contract with the plugin's ConfigPublishService is
// unit-tested; the plugin owns the keys.

export interface ConfigRow {
  config_key: string;
  config_value: string;
  updated_at: Date;
}

/** The public REST contract (treasury-rest-api TaxConfigResponse). Rates are fractions as decimal strings. */
export interface TaxConfig {
  balanceTax: {
    enabled: boolean;
    updatedAt: string;
    /** Ascending by floor. */
    brackets: { floor: string; weeklyRate: string }[];
  } | null;
  sourceIncomeTax: {
    enabled: boolean;
    updatedAt: string;
    defaultRate: string;
    pluginRates: { plugin: string; rate: string }[];
  } | null;
}

/** What the docs' live blocks show (components/docs/LiveDocBlocks.tsx). */
export interface TaxRates {
  balanceTax: {
    enabled: boolean;
    /** Ascending; the whole balance pays the rate of the band it falls in. */
    brackets: { band: string; rate: string }[];
    updatedAt: string;
  } | null;
  incomeTax: {
    enabled: boolean;
    defaultRate: string;
    sources: { plugin: string; rate: string }[];
    updatedAt: string;
  } | null;
}

/** A published fraction ("0.012") as a percentage ("1.2%"). */
export function fmtRate(fraction: string): string {
  const pct = Number(fraction) * 100;
  if (!Number.isFinite(pct)) return '—';
  return pct.toLocaleString('en-US', { maximumFractionDigits: 4 }) + '%';
}

/** Brackets in ascending floor order, compared as exact money. */
export function sortBrackets<T extends { floor: string }>(brackets: readonly T[]): T[] {
  return [...brackets].sort((a, b) => compare(tryParseMoney(a.floor) ?? 0n, tryParseMoney(b.floor) ?? 0n));
}

function latest(...rows: ConfigRow[]): string {
  return new Date(Math.max(...rows.map((r) => r.updated_at.getTime()))).toISOString();
}

function isBracket(b: unknown): b is { floor: string; rate: string } {
  if (typeof b !== 'object' || b === null) return false;
  const { floor, rate } = b as Record<string, unknown>;
  return typeof floor === 'string' && typeof rate === 'string';
}

/**
 * The economy_config fallback in the REST contract's shape. A section is null
 * when any of its keys is missing or unreadable (not yet published), exactly
 * as treasury-rest-api's EconomyConfigService decides.
 */
export function taxConfigFromRows(rows: readonly ConfigRow[]): TaxConfig {
  const by = new Map(rows.map((r) => [r.config_key, r]));
  let balanceTax: TaxConfig['balanceTax'] = null;
  const enabled = by.get('tax.balance.enabled');
  const brackets = by.get('tax.balance.brackets');
  if (enabled && brackets) {
    try {
      const parsed: unknown = JSON.parse(brackets.config_value);
      if (Array.isArray(parsed) && parsed.every(isBracket)) {
        balanceTax = {
          enabled: enabled.config_value === 'true',
          updatedAt: latest(enabled, brackets),
          brackets: sortBrackets(parsed.map((b) => ({ floor: b.floor, weeklyRate: b.rate }))),
        };
      }
    } catch { /* unpublished */ }
  }
  let sourceIncomeTax: TaxConfig['sourceIncomeTax'] = null;
  const incomeEnabled = by.get('tax.source-income-tax.enabled');
  const defaultRate = by.get('tax.source-income-tax.default-rate');
  const pluginRates = by.get('tax.source-income-tax.plugin-rates');
  if (incomeEnabled && defaultRate && pluginRates) {
    try {
      const parsed: unknown = JSON.parse(pluginRates.config_value);
      if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
        sourceIncomeTax = {
          enabled: incomeEnabled.config_value === 'true',
          updatedAt: latest(incomeEnabled, defaultRate, pluginRates),
          defaultRate: defaultRate.config_value,
          pluginRates: Object.entries(parsed as Record<string, unknown>)
            .filter((e): e is [string, string] => typeof e[1] === 'string')
            .map(([plugin, rate]) => ({ plugin, rate })),
        };
      }
    } catch { /* unpublished */ }
  }
  return { balanceTax, sourceIncomeTax };
}

/** The docs' display shape from the shared contract. */
export function docTaxRates(config: TaxConfig): TaxRates {
  const b = config.balanceTax;
  const i = config.sourceIncomeTax;
  return {
    balanceTax: b && {
      enabled: b.enabled,
      brackets: b.brackets.map((br, n) => {
        const next = b.brackets[n + 1];
        return { band: next ? `${fmtAmtFull(br.floor)} up to ${fmtAmtFull(next.floor)}` : `${fmtAmtFull(br.floor)} and above`, rate: fmtRate(br.weeklyRate) };
      }),
      updatedAt: b.updatedAt,
    },
    incomeTax: i && {
      enabled: i.enabled,
      defaultRate: fmtRate(i.defaultRate),
      sources: i.pluginRates.map((p) => ({ plugin: p.plugin, rate: fmtRate(p.rate) })),
      updatedAt: i.updatedAt,
    },
  };
}

/** economy_config rows straight to the docs shape (the fallback path). */
export function parseTaxRates(rows: ConfigRow[]): TaxRates {
  return docTaxRates(taxConfigFromRows(rows));
}
