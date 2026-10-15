import 'server-only';
import { z } from 'zod';

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
export interface TreasuryTaxRates {
  balanceTax: z.infer<typeof balance> | null;
  sourceIncomeTax: z.infer<typeof income> | null;
}

/** Public PAR-80 REST contract. No admin credentials or static-default fallback. */
export async function fetchTreasuryTaxRates(): Promise<TreasuryTaxRates> {
  const unavailable = { balanceTax: null, sourceIncomeTax: null };
  const base = process.env.TREASURY_API_BASE_URL?.replace(/\/+$/, '').replace(/\/api\/v1$/, '');
  if (!base) return unavailable;
  try {
    const response = await fetch(`${base}/api/v1/config/tax`, {
      cache: 'no-store', signal: AbortSignal.timeout(3000), redirect: 'error',
    });
    if (!response.ok) return unavailable;
    const json = await response.json();
    const parsedBalance = balance.safeParse(json?.balanceTax);
    const parsedIncome = income.safeParse(json?.sourceIncomeTax);
    return {
      balanceTax: parsedBalance.success ? {
        ...parsedBalance.data,
        brackets: parsedBalance.data.brackets.sort((a, b) => Number(a.floor) - Number(b.floor)),
      } : null,
      sourceIncomeTax: parsedIncome.success ? parsedIncome.data : null,
    };
  } catch {
    return unavailable;
  }
}
