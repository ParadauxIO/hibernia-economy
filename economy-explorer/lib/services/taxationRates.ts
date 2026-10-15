import 'server-only';
import { memo } from '@/lib/cache';
import { fetchTreasuryTaxRates } from '@/lib/treasuryTaxRates';

export const getTaxationRates = () => memo('taxation-rates:v1', 60_000, fetchTreasuryTaxRates);
