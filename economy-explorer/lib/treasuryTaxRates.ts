import 'server-only';
import { z } from 'zod';
import { sortBrackets, type TaxConfig } from '@/lib/taxRates';

const decimal = z.string().regex(/^\d+(\.\d+)?$/).refine(v => Number.isFinite(Number(v)));
const rate = decimal.refine(v => Number(v) <= 1);
const timestamp = z.string().datetime({ offset: true });
const balance = z.object({
  enabled: z.boolean(), updatedAt: timestamp,
  brackets: z.array(z.object({ floor: decimal, weeklyRate: rate })),
});
const income = z.object({
  enabled: z.boolean(), updatedAt: timestamp, defaultRate: rate,
  pluginRates: z.array(z.object({ plugin: z.string(), rate })),
});
export type TreasuryTaxRates = TaxConfig;

/**
 * The public PAR-80 REST contract, or null when the endpoint can't be reached
 * (unconfigured, non-2xx, timeout, unreadable body). No admin credentials.
 * A malformed section is null on its own; the other still comes through.
 */
export async function fetchTaxConfig(): Promise<TaxConfig | null> {
  const base = process.env.TREASURY_API_BASE_URL?.replace(/\/+$/, '').replace(/\/api\/v1$/, '');
  if (!base) return null;
  try {
    const response = await fetch(`${base}/api/v1/config/tax`, {
      cache: 'no-store', signal: AbortSignal.timeout(3000), redirect: 'error',
    });
    if (!response.ok) return null;
    const json = await response.json();
    const parsedBalance = balance.safeParse(json?.balanceTax);
    const parsedIncome = income.safeParse(json?.sourceIncomeTax);
    return {
      balanceTax: parsedBalance.success ? { ...parsedBalance.data, brackets: sortBrackets(parsedBalance.data.brackets) } : null,
      sourceIncomeTax: parsedIncome.success ? parsedIncome.data : null,
    };
  } catch {
    return null;
  }
}

/** The REST contract with an unreachable endpoint shown as unpublished sections. */
export async function fetchTreasuryTaxRates(): Promise<TreasuryTaxRates> {
  return (await fetchTaxConfig()) ?? { balanceTax: null, sourceIncomeTax: null };
}
