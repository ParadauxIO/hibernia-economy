import { CATEGORY_LABELS, categorize, type Category } from '@/lib/categories';
import { ACCOUNT_TYPES } from './accounts';
import { defineTable, type EnumOption, type TableState } from './core';

// The transaction explorer (`/transactions`, spec §9.4) and its export.
//
// The plugin filter's options are the plugins this tenant's ledger actually
// has (DemocracyCraft and StateCraft run different ones), so the table is built
// from that list: `transactionsTable(plugins)`. The service caches the list.
// Everything else is static.
//
// Every filter is index-backed (see lib/sql/ledger.ts, "Transaction explorer"):
//   plugin          → idx_ledger_plugin_time
//   category        → idx_ledger_plugin_time per plugin, idx_ledger_tax_kind_time for Taxes
//   settled         → idx_ledger_settle_time
//   player          → idx_ledger_initiator / idx_ledger_authorizer
//   account         → idx_postings_account_txn_amount
// Amount reads ledger_txn_volume's narrow (settlement_time, volume) index, so it
// may scan AMOUNT_WINDOW_DAYS (spec §11's 31-day cap). The account types and
// message text are checked against each transaction's rows, which costs far
// more per row, so they scan at most ROW_SCAN_DAYS.

/** URL value for transactions with no plugin_system. Never a real plugin name (they can't start with `_`). */
export const NO_PLUGIN = '_none';

/** Longest window the amount filter may scan (spec §11: 31 days of raw ledger). */
export const AMOUNT_WINDOW_DAYS = 31;
/** Longest window the account-type and message filters may scan. */
export const ROW_SCAN_DAYS = 7;

const PLUGIN_VALUE_RE = /^[a-zA-Z0-9-][a-zA-Z0-9_-]{0,39}$/;

/** Plugin names usable as enum option values; others can't be expressed in a URL chip. */
export function pluginOptions(plugins: readonly (string | null)[]): EnumOption[] {
  const out: EnumOption[] = [];
  const seen = new Set<string>();
  for (const p of plugins) {
    const value = p === null || p === '' ? NO_PLUGIN : p;
    if (value !== NO_PLUGIN && !PLUGIN_VALUE_RE.test(value)) continue;
    if (seen.has(value.toLowerCase())) continue;
    seen.add(value.toLowerCase());
    out.push({ value, label: value === NO_PLUGIN ? 'No plugin' : value });
  }
  return out.length ? out : [{ value: NO_PLUGIN, label: 'No plugin' }];
}

// ── Categories (lib/categories.ts) at the transaction level ──────────────
//
// categorize() describes one flow seen from one account (direction,
// counterparty). A transaction in the explorer has no subject account, so its
// category is Taxes when V38's tax_kind is set, otherwise its plugin's
// category. Two simplifications follow, and the filter hint says so:
//   * ChestShop is "Shop trades": a sale for the shop is a purchase for the customer.
//   * Treasury's own transfers count as Transfers whoever the counterparty is.
// Internal depends on the account looking at it, so it is not offered. Each
// category is therefore a set of plugins (plus tax_kind for Taxes), and the
// filter stays on idx_ledger_plugin_time and idx_ledger_tax_kind_time.

export const TXN_CATEGORIES: readonly Category[] = [
  'wages', 'shop_sales', 'taxes', 'fines', 'property', 'business', 'crime', 'jobs', 'transfers', 'api', 'other',
];

export const CATEGORY_OPTIONS: readonly EnumOption[] = TXN_CATEGORIES.map((c) => ({ value: c, label: c === 'shop_sales' ? 'Shop trades' : CATEGORY_LABELS[c] }));

/** The category of a plugin's untaxed transactions, at the transaction level. */
export function pluginCategory(plugin: string | null): Category {
  const c = categorize({ pluginKey: plugin ?? '', taxKind: null, counterpartyType: 'PERSONAL', direction: 'in', internal: false });
  return c === 'shop_purchases' ? 'shop_sales' : c;
}

/** Category chip values → the plugins whose untaxed transactions match, and whether taxed ones do. */
export function categoryPlugins(categories: readonly string[], plugins: readonly (string | null)[]): { plugins: (string | null)[]; taxes: boolean } {
  const wanted = new Set(categories);
  return { plugins: plugins.filter((p) => wanted.has(pluginCategory(p))), taxes: wanted.has('taxes') };
}

export function transactionsTable(plugins: readonly (string | null)[] = []) {
  return defineTable({
    id: 'transactions',
    search: ['txnId', 'message'],
    searchPlaceholder: 'Search messages or #transaction',
    filters: {
      settled: { label: 'Settled', kind: 'date' },
      category: { label: 'Category', kind: 'enum', options: CATEGORY_OPTIONS, hint: 'Taxes, or the recording plugin’s category' },
      plugin: { label: 'Plugin', kind: 'enum', options: pluginOptions(plugins), hint: 'The plugin that recorded the transfer' },
      account: { label: 'Account', kind: 'entity', entity: 'account', hint: 'Transactions with a posting on this account' },
      player: { label: 'Initiator or authorizer', kind: 'entity', entity: 'player', hint: 'Who started or approved the transfer' },
      amount: { label: 'Amount', kind: 'money', hint: `Total moved. Searches up to ${AMOUNT_WINDOW_DAYS} days` },
      fromType: { label: 'From type', kind: 'enum', options: ACCOUNT_TYPES, hint: `Type of a paying account. Searches up to ${ROW_SCAN_DAYS} days` },
      toType: { label: 'To type', kind: 'enum', options: ACCOUNT_TYPES, hint: `Type of a receiving account. Searches up to ${ROW_SCAN_DAYS} days` },
    },
    sorts: { settled: 'Settled' },
    defaultSort: '-settled',
    quick: [
      { label: 'Today', f: ['settled:gte:-1d'] },
      { label: 'Over 100k this week', f: ['amount:gt:100000', 'settled:gte:-7d'] },
      { label: 'Taxes', f: ['category:in:taxes'] },
      { label: 'Paid to government', f: ['toType:in:government', 'settled:gte:-7d'] },
    ],
    pagination: 'cursor',
    pageSize: 50,
    maxPageSize: 200,
    noun: { one: 'transaction', many: 'transactions' },
  });
}

export type TransactionsTable = ReturnType<typeof transactionsTable>;
export type TransactionsTableState = TableState<TransactionsTable>;
