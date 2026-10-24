import { fmtAmtFull } from '@/lib/format';

// Shapes the tax rates Treasury publishes into economy_config (PAR-80) for the
// live blocks on the tax docs page. Pure, so the encoding contract with the
// plugin's ConfigPublishService is unit-tested; the plugin owns the keys.

export interface ConfigRow {
  config_key: string;
  config_value: string;
  updated_at: Date;
}

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

function latest(...rows: ConfigRow[]): string {
  return new Date(Math.max(...rows.map((r) => r.updated_at.getTime()))).toISOString();
}

/** A section is null when any of its keys is missing or unreadable (not yet published). */
export function parseTaxRates(rows: ConfigRow[]): TaxRates {
  const by = new Map(rows.map((r) => [r.config_key, r]));
  return { balanceTax: balanceTax(by), incomeTax: incomeTax(by) };
}

type Bracket = { floor: string; rate: string };

function isBracket(b: unknown): b is Bracket {
  if (typeof b !== 'object' || b === null) return false;
  const { floor, rate } = b as Record<string, unknown>;
  return typeof floor === 'string' && typeof rate === 'string';
}

function balanceTax(by: Map<string, ConfigRow>): TaxRates['balanceTax'] {
  const enabled = by.get('tax.balance.enabled');
  const brackets = by.get('tax.balance.brackets');
  if (!enabled || !brackets) return null;
  let list: Bracket[];
  try {
    const parsed: unknown = JSON.parse(brackets.config_value);
    if (!Array.isArray(parsed) || !parsed.every(isBracket)) return null;
    list = parsed;
  } catch {
    return null;
  }
  return {
    enabled: enabled.config_value === 'true',
    brackets: list.map((b, i) => {
      const next = list[i + 1];
      const band = next
        ? `${fmtAmtFull(b.floor)} up to ${fmtAmtFull(next.floor)}`
        : `${fmtAmtFull(b.floor)} and above`;
      return { band, rate: fmtRate(b.rate) };
    }),
    updatedAt: latest(enabled, brackets),
  };
}

function incomeTax(by: Map<string, ConfigRow>): TaxRates['incomeTax'] {
  const enabled = by.get('tax.source-income-tax.enabled');
  const defaultRate = by.get('tax.source-income-tax.default-rate');
  const pluginRates = by.get('tax.source-income-tax.plugin-rates');
  if (!enabled || !defaultRate || !pluginRates) return null;
  let rates: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(pluginRates.config_value);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    rates = parsed as Record<string, unknown>;
  } catch {
    return null;
  }
  return {
    enabled: enabled.config_value === 'true',
    defaultRate: fmtRate(defaultRate.config_value),
    sources: Object.entries(rates)
      .filter((e): e is [string, string] => typeof e[1] === 'string')
      .map(([plugin, rate]) => ({ plugin, rate: fmtRate(rate) })),
    updatedAt: latest(enabled, defaultRate, pluginRates),
  };
}
