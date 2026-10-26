import 'server-only';
import { getTaxConfig } from './taxRates';

/** The taxation page's rates: the shared tax-rate source (lib/services/taxRates.ts). */
export const getTaxationRates = getTaxConfig;
