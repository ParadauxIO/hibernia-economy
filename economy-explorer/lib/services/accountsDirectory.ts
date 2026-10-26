import 'server-only';
import { memo } from '@/lib/cache';
import { countAccounts, countAccountsByType, listAccounts, type ExplorerAccountRow } from '@/lib/sql/ledger';
import { findFirmNames, findFirmsByPrefix, type EntityOption } from '@/lib/sql/tableLookups';
import { accountsTable, type AccountsTableState } from '@/lib/tables/accounts';
import { isQuickActive, quickFilters, serializeTableState } from '@/lib/tables/core';

export interface AccountsDirectory {
  rows: ExplorerAccountRow[];
  total: number;
  /** Type facet counts for the filter popover, keyed by option value. */
  typeCounts: Record<string, number>;
  /** Chip labels for entity filters, keyed `field:value`. */
  entityLabels: Record<string, string>;
}

const TYPE_VALUES: Record<string, string> = { PERSONAL: 'personal', BUSINESS: 'business', GOVERNMENT: 'government', SYSTEM: 'system' };

/** Type counts, for active or archived accounts. One cached pass for both. */
async function typeCounts(archived: boolean): Promise<Record<string, number>> {
  const rows = await memo('accounts-dir:type-facets', 10 * 60_000, countAccountsByType);
  const out: Record<string, number> = {};
  for (const r of rows) {
    const key = TYPE_VALUES[r.account_type];
    if (key && r.archived === (archived ? 1 : 0)) out[key] = (out[key] ?? 0) + r.count;
  }
  return out;
}

/**
 * Only views a link can lead to are cached: the default browse and the quick
 * filters, per sort and page. Typed searches and custom ranges vary per request
 * and would only fill the cache.
 */
function cacheable(state: AccountsTableState): boolean {
  if (state.q !== '') return false;
  if (state.filters.length === 0) return true;
  return quickFilters(accountsTable).some((quick) => quick.filters.length === state.filters.length && isQuickActive(state, quick));
}

/** One page of the public accounts directory, its count, facets and chip labels. */
export async function getAccountsDirectory(state: AccountsTableState): Promise<AccountsDirectory> {
  const offset = (state.page - 1) * state.limit;
  const archived = state.filters.some((f) => f.field === 'archived' && f.values[0] === 'true');
  const fetchPage = () => Promise.all([
    listAccounts({ state, limit: state.limit, offset }),
    countAccounts({ state }),
  ]);
  const firmIds = state.filters.filter((f) => f.field === 'firm').flatMap((f) => f.values.map(Number));

  const [[rows, total], counts, firms] = await Promise.all([
    cacheable(state) ? memo(`accounts-dir:v2:${serializeTableState(state, accountsTable)}`, 60_000, fetchPage) : fetchPage(),
    typeCounts(archived),
    findFirmNames(firmIds),
  ]);
  const entityLabels: Record<string, string> = {};
  for (const [id, name] of firms) entityLabels[`firm:${id}`] = name;
  return { rows, total, typeCounts: counts, entityLabels };
}

/** Typeahead for the Firm filter. Prefix match only, so it stays on the name index. */
export async function suggestFirms(prefix: string): Promise<EntityOption[]> {
  const term = prefix.trim().slice(0, 64);
  if (term.length === 0) return [];
  return findFirmsByPrefix(term, 8);
}
