// Accounts directory filters (PAR-534) against the seeded MariaDB. Registered from
// sql.test.ts so it shares that file's single resetDb() and runs serially with it.
import { sql } from 'kysely';
import { expect, it } from 'vitest';
import { db } from '@/lib/db';
import { getAccountsDirectory, suggestFirms } from '@/lib/services/accountsDirectory';
import { countAccounts, countAccountsByType, listAccounts } from '@/lib/sql/ledger';
import { findFirmNames, findFirmsByPrefix } from '@/lib/sql/tableLookups';
import { accountsTable } from '@/lib/tables/accounts';
import { parseTableState } from '@/lib/tables/core';
import { ALICE } from './db';

const state = (qs: string) => parseTableState(new URLSearchParams(qs), accountsTable);

/** Seeded accounts only: other suites add temporary 9xxx rows. */
async function ids(qs: string): Promise<number[]> {
  const rows = await listAccounts({ state: state(qs), limit: 200, offset: 0 });
  return rows.map((r) => r.account_id).filter((id) => id < 9000);
}

export function accountsTableSuite(d: (name: string, fn: () => void) => void): void {
  d('accounts directory filters (PAR-534)', () => {
    it('lists active accounts by balance, high to low, by default', async () => {
      const rows = await ids('');
      expect(rows.slice(0, 5)).toEqual([2, 3, 5, 1, 7]);
      expect(rows[rows.length - 1]).toBe(4); // the minting account, negative, is shown as it is
      expect(rows).not.toContain(6); // archived
    });

    it('filters by type through the stored ENUM', async () => {
      expect(await ids('f=type:in:business')).toEqual([3]);
      expect((await ids('f=type:in:government,system')).sort()).toEqual([4, 5]);
    });

    it('filters balance exactly as DECIMAL, including negatives and inclusive bounds', async () => {
      expect(await ids('f=balance:gt:10k')).toEqual([2, 3]);
      expect(await ids('f=balance:between:1500,8000')).toEqual([5, 1, 7]);
      expect(await ids('f=balance:lt:0')).toEqual([4]);
      expect(await ids('f=balance:eq:25000')).toEqual([3]);
    });

    it('shows archived accounts only when asked', async () => {
      expect(await ids('f=archived:eq:true')).toEqual([6]);
      expect(await ids('f=archived:eq:false')).not.toContain(6);
    });

    it('filters by firm through firm_accounts', async () => {
      expect(await ids('f=firm:in:1')).toEqual([3]);
      expect(await ids('f=firm:in:2')).toEqual([]);
    });

    it('filters created dates as half-open UTC ranges', async () => {
      const [before] = (await sql<{ created_at: Date }>`SELECT created_at FROM accounts WHERE account_id = 5`.execute(db)).rows;
      await sql`UPDATE accounts SET created_at = '2024-05-01 12:00:00' WHERE account_id = 5`.execute(db);
      try {
        expect(await ids('f=created:eq:2024')).toEqual([5]);
        expect(await ids('f=created:eq:2024-05-01')).toEqual([5]);
        expect(await ids('f=created:eq:2024-04')).toEqual([]);
        expect(await ids('f=created:gte:-30d')).not.toContain(5);
        expect(await ids('f=created:lt:2025&f=type:in:government')).toEqual([5]);
      } finally {
        await sql`UPDATE accounts SET created_at = ${before!.created_at} WHERE account_id = 5`.execute(db);
      }
    });

    it('combines filters with AND and counts the same set it lists', async () => {
      const qs = 'f=type:in:personal&f=balance:gte:1500';
      expect(await ids(qs)).toEqual([2, 1, 7]);
      expect(await countAccounts({ state: state(qs) })).toBe(3);
      expect(await countAccounts({ state: state('f=firm:in:1&f=balance:gt:1') })).toBe(1);
    });

    it('searches: exact UUID owner match, #id, and names (account and owner)', async () => {
      expect((await ids(`q=${ALICE}`)).sort()).toEqual([1, 3]);
      expect((await ids(`q=${ALICE.replace(/-/g, '').toUpperCase()}`)).sort()).toEqual([1, 3]);
      expect(await ids('q=%235')).toEqual([5]);
      expect(await ids('q=city')).toEqual([5]);
      expect((await ids('q=alice')).sort()).toEqual([1, 3]); // Alice's personal account and the firm account she owns
      // A partial UUID no longer scans HEX(owner_uuid_bin): it only matches a
      // display name that contains it (#1 stores its owner's UUID as its name),
      // not every account the owner holds (#3).
      expect(await ids('q=a1ce')).toEqual([1]);
      // LIKE wildcards are literal.
      expect(await ids('q=%25')).toEqual([]);
      expect(await ids('q=_')).toEqual([]);
      expect(await countAccounts({ state: state('q=alice') })).toBe(2);
    });

    it('sorts by the label people see, not the UUID stored as a personal display name', async () => {
      const rows = await ids('sort=name');
      const order = [3, 1, 2, 5].map((id) => rows.indexOf(id)); // Acme…, Alice, Bob, City Hall
      expect(order).toEqual([...order].sort((a, b) => a - b));
      expect(order.every((i) => i >= 0)).toBe(true);
    });

    it('pages with a stable order', async () => {
      const page1 = await listAccounts({ state: state('sort=-created'), limit: 3, offset: 0 });
      const page2 = await listAccounts({ state: state('sort=-created'), limit: 3, offset: 3 });
      const seen = [...page1, ...page2].map((r) => r.account_id);
      expect(new Set(seen).size).toBe(seen.length);
    });

    it('still serves the admin tool’s legacy argument shape', async () => {
      const rows = await listAccounts({ limit: 25, offset: 0, type: 'PERSONAL', archived: null, q: null, sort: 'account_id', dir: 'ASC' });
      const personal = rows.map((r) => r.account_id).filter((id) => id < 9000);
      expect(personal.slice(0, 3)).toEqual([1, 2, 6]); // archived included when archived is null
      expect(await countAccounts({ type: 'BUSINESS', archived: 0, q: null })).toBe(1);
    });

    it('counts accounts by type and archived state for the facet counts', async () => {
      const rows = (await countAccountsByType()).filter((r) => r.archived === 0);
      const business = rows.find((r) => r.account_type === 'BUSINESS');
      expect(business?.count).toBeGreaterThanOrEqual(1);
      expect((await countAccountsByType()).find((r) => r.account_type === 'PERSONAL' && r.archived === 1)?.count).toBeGreaterThanOrEqual(1);
    });

    it('serves a directory page with facets and firm chip labels', async () => {
      const dir = await getAccountsDirectory(state('f=firm:in:1'));
      expect(dir.rows.map((r) => r.account_id)).toEqual([3]);
      expect(dir.total).toBe(1);
      expect(dir.entityLabels).toEqual({ 'firm:1': 'Acme Corp' });
      expect(dir.typeCounts.business).toBeGreaterThanOrEqual(1);
    });

    it('suggests firms by name prefix only, with wildcards escaped', async () => {
      expect(await findFirmsByPrefix('ac')).toEqual([{ value: '1', label: 'Acme Corp' }]);
      expect(await findFirmsByPrefix('corp')).toEqual([]); // prefix, not substring
      expect(await findFirmsByPrefix('%')).toEqual([]);
      expect(await suggestFirms('   ')).toEqual([]);
      expect(await findFirmNames([1, 2, 404])).toEqual(new Map([[1, 'Acme Corp'], [2, 'TaxFree Co']]));
    });
  });
}
