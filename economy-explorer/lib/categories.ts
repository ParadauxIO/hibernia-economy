// Category taxonomy (spec §4.4), shared by /me, the account page and /gov.
// Pure and table-driven: the plugin map below is data, so a mapping decision is
// a one-line change and rollups never need a backfill (they store plugin_key
// and tax_kind as-is; categories are applied at read time).
//
// StateCraft may run plugins DemocracyCraft doesn't. The map is shared; an
// unknown plugin falls into Other and is logged once per process so it can be
// given a mapping.

export const CATEGORIES = [
  'wages', 'shop_sales', 'shop_purchases', 'taxes', 'fines', 'property', 'business',
  'crime', 'jobs', 'transfers', 'api', 'internal', 'other',
] as const;
export type Category = (typeof CATEGORIES)[number];

export const CATEGORY_LABELS: Record<Category, string> = {
  wages: 'Wages & salary',
  shop_sales: 'Shop sales',
  shop_purchases: 'Shop purchases',
  taxes: 'Taxes',
  fines: 'Fines',
  property: 'Property',
  business: 'Business',
  crime: 'Crime & bounties',
  jobs: 'Jobs & activities',
  transfers: 'Transfers',
  api: 'API',
  internal: 'Internal',
  other: 'Other',
};

export type Direction = 'in' | 'out';
export type AccountType = 'PERSONAL' | 'BUSINESS' | 'GOVERNMENT' | 'SYSTEM';

export interface CategoryInput {
  /** `COALESCE(ledger_txns.plugin_system, '')`, any case. */
  pluginKey: string;
  /** V38 `tax_kind` (personal, corporate, income, sales), 'fine' for a linked
   * government fine or its revocation, or ''/null. */
  taxKind: string | null;
  /** The other side's account type, when known. */
  counterpartyType: AccountType | null;
  /** Relative to the subject account: money in or out. */
  direction: Direction;
  /** Moves money between the subject's own accounts (excluded from income and spending). */
  internal: boolean;
}

/** A plugin's category, optionally split by direction or limited by counterparty. */
type Rule =
  | Category
  | { in: Category; out: Category }
  | { category: Category; counterparty: readonly AccountType[]; otherwise: Category };

const PLAYERS: readonly AccountType[] = ['PERSONAL', 'BUSINESS'];

/** Keys are lower case; lookups are case-insensitive like the ledger's collation. */
export const PLUGIN_CATEGORIES: Readonly<Record<string, Rule>> = {
  'treasury-salary': 'wages',
  chestshop: { in: 'shop_sales', out: 'shop_purchases' },
  realty: 'property',
  griefprevention: 'property',
  griefpreventionui: 'property',
  griefprevgui: 'property',
  businessplugin: 'business',
  business: 'business',
  democracyrobbery: 'crime',
  bountyhunters: 'crime',
  homelandsecurity: 'fines',
  exams: 'jobs',
  nightcore: 'jobs',
  customfishing: 'jobs',
  mobstoeggs: 'jobs',
  deluxemenus: 'jobs',
  // Treasury's own transfers are Transfers only between players and firms;
  // admin payouts and seeding from government or system accounts are not.
  'treasury-core': { category: 'transfers', counterparty: PLAYERS, otherwise: 'other' },
  treasury: { category: 'transfers', counterparty: PLAYERS, otherwise: 'other' },
  // /pay without a plugin, and Essentials /pay through its Vault bridge.
  '': 'transfers',
  essentials: 'transfers',
  'rest-api': 'api',
  // Known, deliberately Other: postage, Discord rewards, minting and imports.
  democracypost: 'other',
  postmanpat: 'other',
  discordsrv: 'other',
  eco: 'other',
  treasuryingest: 'other',
};

const reported = new Set<string>();

function unknownPlugin(key: string): Category {
  if (!reported.has(key)) {
    reported.add(key);
    console.warn(`[categories] no category for plugin_key "${key}"; using Other. Add it to PLUGIN_CATEGORIES.`);
  }
  return 'other';
}

/** The category of one flow. Order: internal, fines, taxes, then the plugin map. */
export function categorize(input: CategoryInput): Category {
  if (input.internal) return 'internal';
  const taxKind = input.taxKind?.trim().toLowerCase() || null;
  if (taxKind === 'fine') return 'fines';
  if (taxKind) return 'taxes';
  const key = input.pluginKey.trim().toLowerCase();
  const rule = Object.hasOwn(PLUGIN_CATEGORIES, key) ? PLUGIN_CATEGORIES[key] : undefined;
  if (rule === undefined) return unknownPlugin(key);
  if (typeof rule === 'string') return rule;
  if ('in' in rule) return rule[input.direction];
  return input.counterpartyType && rule.counterparty.includes(input.counterpartyType) ? rule.category : rule.otherwise;
}

/** Whether a category counts towards income and spending (Internal does not). */
export function countsAsIncomeOrSpending(category: Category): boolean {
  return category !== 'internal';
}
