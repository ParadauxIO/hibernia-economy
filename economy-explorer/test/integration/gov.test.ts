import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import mysql from 'mysql2/promise';
import { sql } from 'kysely';

// The /gov scope matrix and every /gov query against a real MariaDB with real
// rollups (spec §10, PAR-539). Like rollup.test.ts this suite owns its database
// (DB_NAME + '_gov') because sql.test.ts resets the shared one.
vi.hoisted(() => {
  process.env.DB_NAME = `${process.env.DB_NAME ?? 'economy_test'}_gov`;
});
vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));

import { HAS_DB, resetDb, testDbOptions, uuid, ALICE, BOB, CAROL, DAVE } from './db';
import { permissionsFor } from '../role-fixtures';
import { db } from '@/lib/db';
import { getViewer, type Viewer } from '@/lib/auth/viewer';
import { closeRollupDb, rollupDb } from '@/lib/rollup/db';
import { addDays, eachDay, utcDay } from '@/lib/rollup/days';
import { markHistoryComplete, recomputeDay, ROLLUP_JOBS } from '@/lib/rollup/steps';
import { resetCacheForTests } from '@/lib/cache';
import { parseTableState } from '@/lib/tables/core';
import { finesTable } from '@/lib/tables/fines';
import { govLedgerExportTable, govLedgerTable, govViewsTable } from '@/lib/tables/gov';
import '@/lib/export/datasets';
import { getExport } from '@/lib/export/registry';
import { govSelection, resolveGovScope, type GovScope } from '@/lib/services/governmentScope';
import {
  getFines, getGovAccounts, getLedgerAudit, getMonthlyReport, getOverview, getPayroll, getRevenue, getSpending, getViewsAudit,
} from '@/lib/services/government';
import { getFirmInvestigation, setWatched, getComplianceHome, type ComplianceViewer } from '@/lib/services/governmentCompliance';
import { getGovUnitsAdmin, removeGovUnit, saveGovUnit, setAccountUnit } from '@/lib/services/governmentUnits';
import { listWatchlist } from '@/lib/sql/compliance';

const d = HAS_DB ? describe : describe.skip;

const OFFICIAL = uuid('0000000000000000000000000000F00D');
const GOV = '000000000000000000000000000060AA';
const LP_GROUP = 'department-of-commerce';
const TODAY = utcDay();
const D3 = addDays(TODAY, -3);
const D2 = addDays(TODAY, -2);
const D1 = addDays(TODAY, -1);
const RANGE = { from: addDays(TODAY, -6), to: TODAY };
const IN_SCOPE = [102, 103];
const OUT_OF_SCOPE = [5, 101, 104, 105];

const viewer = (uuidValue: string | null, ...roles: string[]): Viewer => ({
  anon: false, keycloakSub: `sub-${uuidValue}`, minecraftUuid: uuidValue, minecraftName: null, linked: uuidValue !== null,
  role: roles[0] ?? 'player', capabilities: ['player', ...roles], permissions: permissionsFor('player', ...roles),
});
const official = viewer(OFFICIAL, 'government');
const moderator = viewer(CAROL, 'moderator');
const admin = viewer(ALICE, 'admin');

let nextTxn = 7000;
async function txn(when: string, plugin: string, message: string, legs: [number, string][], initiator = '00000000000000000000000000000000', authorizer: string | null = null): Promise<number> {
  const id = ++nextTxn;
  await sql`INSERT INTO ledger_txns (txn_id, settlement_time, message, initiator_uuid_bin, authorizer_uuid_bin, plugin_system)
    VALUES (${id}, ${when}, ${message}, UNHEX(${initiator}), ${authorizer ? sql`UNHEX(${authorizer})` : null}, ${plugin})`.execute(db);
  for (const [account, amount] of legs) await sql`INSERT INTO ledger_postings (txn_id, account_id, amount) VALUES (${id}, ${account}, ${amount})`.execute(db);
  return id;
}
const hex = (u: string) => u.replaceAll('-', '').toUpperCase();

async function seedGov() {
  await sql`INSERT INTO economy_players (player_uuid_bin, current_name) VALUES (UNHEX(${hex(OFFICIAL)}), 'Official')`.execute(db);
  await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name) VALUES
    (101, 'GOVERNMENT', UNHEX(${GOV}), 'National Treasury'),
    (102, 'GOVERNMENT', UNHEX(${GOV}), 'Commerce Dept'),
    (103, 'GOVERNMENT', UNHEX(${GOV}), 'Commerce Reserve'),
    (104, 'GOVERNMENT', UNHEX(${GOV}), 'Trade Office'),
    (105, 'GOVERNMENT', UNHEX(${GOV}), 'Mint'),
    (106, 'GOVERNMENT', UNHEX(${GOV}), 'Closed Dept')`.execute(db);
  await sql`UPDATE accounts SET is_archived = 1 WHERE account_id = 106`.execute(db);
  await sql`INSERT INTO account_balances_mat (account_id, balance) VALUES
    (101, 1000000.00), (102, 50000.00), (103, 20000.00), (104, 5000.00), (105, -2000000.00), (106, 0.00)`.execute(db);
  // OFFICIAL reads 102 directly (VIEWER) and 103 through a synced LuckPerms group.
  await sql`INSERT INTO account_access (account_id, subject_uuid_bin, level, added_by_uuid_bin) VALUES (102, UNHEX(${hex(OFFICIAL)}), 'VIEWER', UNHEX(${hex(ALICE)}))`.execute(db);
  await sql`INSERT INTO account_group_access (account_id, lp_group, level, added_by_uuid_bin) VALUES (103, ${LP_GROUP}, 'VIEWER', UNHEX(${hex(ALICE)}))`.execute(db);
  await sql`INSERT INTO explorer_lp_group_member (lp_group, player_uuid_bin, synced_at) VALUES (${LP_GROUP}, UNHEX(${hex(OFFICIAL)}), NOW())`.execute(db);
  // Units: commerce {102, 103} with sub-unit trade {104}; treasury {101}; 5 and 105 unassigned.
  await sql`INSERT INTO explorer_gov_unit (unit_id, slug, name, parent_unit_id) VALUES
    (1, 'commerce', 'Commerce', NULL), (2, 'trade', 'Trade', 1), (3, 'treasury', 'Treasury', NULL)`.execute(db);
  await sql`INSERT INTO explorer_gov_unit_account (unit_id, account_id) VALUES (1, 102), (1, 103), (2, 104), (3, 101)`.execute(db);

  const at = (day: string, time: string) => `${day} ${time}`;
  await txn(at(D3, '10:00:00'), 'ChestShop', 'ChestShop sales tax (diamond)', [[1, '-10.00'], [102, '10.00']]);
  await txn(at(D3, '11:00:00'), 'treasury', 'Personal balance tax (24h 0m @ 1.0%/wk)', [[2, '-25.00'], [101, '25.00']]);
  const fine = await txn(at(D2, '09:00:00'), 'treasury', 'Fine: speeding', [[2, '-40.00'], [102, '40.00']]);
  await sql`INSERT INTO government_fines (player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, issued_by_uuid_bin, issued_at)
    VALUES (UNHEX(${hex(BOB)}), 2, 102, 40.00, 'speeding near spawn', ${fine}, UNHEX(${hex(ALICE)}), ${at(D2, '09:00:00')})`.execute(db);
  const otherFine = await txn(at(D2, '09:30:00'), 'treasury', 'Fine: trespass', [[1, '-60.00'], [104, '60.00']]);
  await sql`INSERT INTO government_fines (player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, issued_by_uuid_bin, issued_at)
    VALUES (UNHEX(${hex(ALICE)}), 1, 104, 60.00, 'trespass', ${otherFine}, UNHEX(${hex(CAROL)}), ${at(D2, '09:30:00')})`.execute(db);
  // Salary runs every 15 minutes, then a 45-minute gap: two missed runs.
  for (const [time, to] of [['10:00:00', 1], ['10:15:00', 2], ['10:30:00', 1], ['10:45:00', 1], ['11:30:00', 2]] as const) {
    await txn(at(D2, time), 'treasury-salary', 'Salary', [[102, '-15.00'], [to, '15.00']]);
  }
  await txn(at(D2, '12:00:00'), 'Treasury-Core', 'Admin transfer: grant', [[102, '-500.00'], [3, '500.00']], hex(ALICE), hex(CAROL));
  await txn(at(D2, '13:00:00'), 'treasury', 'reserve top-up', [[101, '-1000.00'], [102, '1000.00']]);
  await txn(at(D1, '09:00:00'), 'treasury', 'move to reserve', [[102, '-200.00'], [103, '200.00']], hex(OFFICIAL));
  await txn(at(D1, '10:00:00'), 'treasury', 'trade payment', [[104, '-77.00'], [3, '77.00']]);
  await txn(at(D1, '11:00:00'), 'Treasury-Core', 'mint', [[105, '-5000.00'], [101, '5000.00']]);
  await txn(at(D1, '12:00:00'), 'ChestShop', 'ChestShop sales tax (iron)', [[3, '-30.00'], [102, '30.00']]);

  // Explorer audit rows: an in-scope view, an out-of-scope view and a workspace page.
  await sql`INSERT INTO explorer_audit (at, actor_sub, actor_uuid_bin, actor_name, actor_role, method, path, target_type, target_id, outcome) VALUES
    (${at(D1, '08:00:00')}, 'sub-a', UNHEX(${hex(ALICE)}), 'Alice', 'admin', 'GET', '/accounts/102', 'account', '102', 200),
    (${at(D1, '08:05:00')}, 'sub-a', UNHEX(${hex(ALICE)}), 'Alice', 'admin', 'GET', '/accounts/104', 'account', '104', 200),
    (${at(D1, '08:10:00')}, 'sub-c', UNHEX(${hex(CAROL)}), 'Carol', 'moderator', 'GET', '/gov', 'global', NULL, 200),
    (${at(D1, '08:15:00')}, 'sub-c', UNHEX(${hex(CAROL)}), 'Carol', 'moderator', 'GET', '/admin/roles', 'global', NULL, 200)`.execute(db);

  // Roll the fixture days up exactly as the worker does, newest first.
  const days = eachDay({ from: addDays(TODAY, -100), to: TODAY }).reverse();
  for (const day of days) for (const job of ROLLUP_JOBS) await recomputeDay(rollupDb(), job, day);
  for (const job of ROLLUP_JOBS) await markHistoryComplete(rollupDb(), job);
}

async function scopeAs(v: Viewer): Promise<GovScope> {
  vi.mocked(getViewer).mockResolvedValue(v);
  const access = await resolveGovScope();
  if (!access.ok) throw new Error(`no scope: ${access.reason}`);
  return access.scope;
}

const accountsOf = (rows: { account_id: number }[]) => [...new Set(rows.map((r) => r.account_id))].sort((a, b) => a - b);

beforeAll(async () => {
  if (!HAS_DB) return;
  const { database, ...server } = testDbOptions();
  const conn = await mysql.createConnection(server);
  try { await conn.query(`CREATE DATABASE IF NOT EXISTS \`${database}\``); } finally { await conn.end(); }
  await resetDb();
  await seedGov();
  resetCacheForTests();
}, 180_000);

afterAll(async () => {
  if (HAS_DB) await closeRollupDb();
});

d('gov scope matrix', () => {
  it('government.read.all: every active government account, the mint included, and every unit', async () => {
    const scope = await scopeAs(moderator);
    expect(scope.all).toBe(true);
    expect(scope.ids.sort((a, b) => a - b)).toEqual([5, 101, 102, 103, 104, 105]);
    expect(scope.units.map((u) => [u.slug, u.partial])).toEqual([['commerce', false], ['trade', false], ['treasury', false]]);
    expect(scope.unassigned.sort((a, b) => a - b)).toEqual([5, 105]);
    // The ledger as it is: the mint's negative balance is in the total.
    const o = await getOverview(scope, govSelection(scope, undefined), RANGE);
    const r = await sql<{ total: string }>`SELECT SUM(abm.balance) AS total FROM accounts a JOIN account_balances_mat abm USING (account_id)
      WHERE a.account_type = 'GOVERNMENT' AND a.is_archived = 0`.execute(db);
    expect(o.treasury).toBe(r.rows[0]?.total);
    expect(o.treasury.startsWith('-')).toBe(true);
  });

  it('relationship scope: direct and LuckPerms-group access, nothing else', async () => {
    const scope = await scopeAs(official);
    expect(scope.all).toBe(false);
    expect(scope.ids.sort((a, b) => a - b)).toEqual(IN_SCOPE);
    expect(scope.canAudit).toBe(true);
  });

  it('partial unit: visible with the readable accounts only, and a count of the rest', async () => {
    const scope = await scopeAs(official);
    expect(scope.units.map((u) => u.slug)).toEqual(['commerce']);
    expect(scope.units[0]).toMatchObject({ readableIds: [102, 103], totalAccounts: 3, partial: true });
    expect(scope.unassigned).toEqual([]);
    expect(govSelection(scope, 'treasury').ids.sort()).toEqual(IN_SCOPE); // invisible unit → All units
    expect(govSelection(scope, 'trade').key).toBe('all');
  });

  it('a stale LuckPerms membership drops out of scope', async () => {
    await sql`UPDATE explorer_lp_group_member SET synced_at = NOW() - INTERVAL 2 HOUR WHERE lp_group = ${LP_GROUP}`.execute(db);
    try {
      expect((await scopeAs(official)).ids).toEqual([102]);
    } finally {
      await sql`UPDATE explorer_lp_group_member SET synced_at = NOW() WHERE lp_group = ${LP_GROUP}`.execute(db);
    }
  });

  it('no scope: government.read without any relationship sees nothing', async () => {
    const scope = await scopeAs(viewer(DAVE, 'government'));
    expect(scope.ids).toEqual([]);
    expect(scope.units).toEqual([]);
    const sel = govSelection(scope, undefined);
    const o = await getOverview(scope, sel, RANGE);
    expect(o.treasury).toBe('0.00');
    expect(o.movements.rows).toEqual([]);
    expect((await getFines(scope, sel.ids, RANGE, parseTableState({}, finesTable))).rows).toEqual([]);
    expect((await getRevenue(scope, sel.ids, RANGE)).taxPayers).toEqual([]);
  });

  it('no permission: players get no scope and no query runs', async () => {
    vi.mocked(getViewer).mockResolvedValue(viewer(BOB));
    expect(await resolveGovScope()).toEqual({ ok: false, reason: 'permission' });
  });
});

d('gov queries stay inside the scope', () => {
  let scope: GovScope;
  let ids: number[];
  beforeAll(async () => {
    scope = await scopeAs(official);
    ids = govSelection(scope, undefined).ids;
  });

  it('overview: totals, net flow, monthly bars, balance and largest movements', async () => {
    const o = await getOverview(scope, govSelection(scope, undefined), RANGE);
    // Opening balances plus the fixture postings (the balance trigger applies them).
    expect(o.treasury).toBe('70505.00');
    expect(o.net).toBe('505.00');
    expect(o.flows.coverage.covered).toBe(true);
    expect(o.flows.breakdown).toMatchObject({ totalRevenue: '80.00', taxes: '40.00', fines: '40.00', totalSpending: '575.00', governmentIn: '1200.00', governmentOut: '200.00' });
    expect(o.months.reduce((s, m) => s + Number(m.revenue), 0)).toBe(80);
    expect(o.balance.data[o.balance.data.length - 1]?.balance).toBe('70505.00');
    expect(o.balance.data[0]?.balance).toBe('70000.00');
    expect(accountsOf(o.movements.rows).every((id) => ids.includes(id))).toBe(true);
    expect(accountsOf(o.movements.rows).filter((id) => OUT_OF_SCOPE.includes(id))).toEqual([]);
    expect(o.movements.rows.slice(0, 3).map((m) => [m.account_id, m.amount])).toEqual([[102, '1000.00'], [102, '-500.00'], [102, '-200.00']]);
    expect(o.units.map((u) => [u.key, u.partial, u.readable, u.total])).toEqual([['commerce', true, 2, 3]]);
  });

  it('accounts: balance, 30-day net, sparkline and unit', async () => {
    const { rows } = await getGovAccounts(scope, ids);
    expect(accountsOf(rows.map((r) => ({ account_id: r.accountId })))).toEqual(IN_SCOPE);
    const commerce = rows.find((r) => r.accountId === 102)!;
    expect(commerce).toMatchObject({ net30: '305.00', unit: { slug: 'commerce', name: 'Commerce' } });
    expect(commerce.spark).toHaveLength(30);
    expect(commerce.spark[commerce.spark.length - 1]).toBe(50305);
  });

  it('revenue: tax kinds, fines and top payers (players and firms)', async () => {
    const r = await getRevenue(scope, ids, RANGE);
    expect(r.flows.breakdown.revenue.map((l) => [l.key, l.amount])).toEqual([['tax:sales', '40.00'], ['fines', '40.00']]);
    expect(r.taxPayers.map((p) => [p.account_id, p.total, p.firm_name])).toEqual([[3, '30.00', 'Acme Corp'], [1, '10.00', null]]);
    expect(r.finePayers.map((p) => [p.account_id, p.total, p.player_name])).toEqual([[2, '40.00', 'Bob']]);
  });

  it('spending: categories, recipient types, recipients and officials', async () => {
    const s = await getSpending(scope, ids, RANGE);
    expect(s.flows.breakdown.spending.map((l) => [l.category, l.amount])).toEqual([['wages', '75.00'], ['transfers', '500.00']]);
    expect(s.flows.breakdown.recipients.map((r) => [r.type, r.amount])).toEqual([['PERSONAL', '75.00'], ['BUSINESS', '500.00'], ['GOVERNMENT', '200.00']]);
    expect(s.recipients.rows.map((c) => [c.counterparty_id, c.outflow])).toEqual([[3, '500.00'], [1, '45.00'], [2, '30.00']]);
    const grant = s.officials.rows.find((o) => o.authorizer_uuid === CAROL);
    expect(grant).toMatchObject({ initiator_uuid: ALICE, initiator_name: 'Alice', authorizer_name: 'Carol', total: '500.00', transactions: 1 });
  });

  it('payroll: daily totals, runs with two missed, recipients', async () => {
    const p = await getPayroll(scope, ids, RANGE);
    expect(p.wagePlugins).toEqual(['treasury-salary']);
    expect(p.total).toBe('75.00');
    expect(p.analysis.runs).toHaveLength(5);
    expect(p.analysis.cadenceMinutes).toBe(15);
    expect(p.analysis.missed).toBe(2);
    expect(p.recipients.map((r) => [r.account_id, r.total, r.payments])).toEqual([[1, '45.00', 3], [2, '30.00', 2]]);
  });

  it('fines: only in-scope fines, chips, summary and export', async () => {
    const all = await getFines(scope, ids, RANGE, parseTableState({}, finesTable));
    expect(all.rows.map((f) => f.gov_account_id)).toEqual([102]);
    expect(all.summary).toEqual({ count: 1, total: '40.00', revoked: 0, revoked_total: '0.00' });
    expect(all.issuers).toEqual([{ issuer_uuid: ALICE, issuer_name: 'Alice', fines: 1, total: '40.00' }]);
    const search = await getFines(scope, ids, RANGE, parseTableState(new URLSearchParams('q=SPAWN&f=status:in:active&f=amount:gte:40'), finesTable));
    expect(search.summary.count).toBe(1);
    expect((await getFines(scope, ids, RANGE, parseTableState(new URLSearchParams(`f=issuer:in:${CAROL}`), finesTable))).summary.count).toBe(0);
    // A chip naming an out-of-scope account can't reach it: scope is ANDed first.
    expect((await getFines(scope, ids, RANGE, parseTableState(new URLSearchParams('f=account:in:104'), finesTable))).rows).toEqual([]);
    // The `fines` export re-resolves the viewer's scope; an account chip only narrows it.
    const fines = getExport('fines')!;
    const signedIn = official as Extract<Viewer, { anon: false }>;
    expect(await fines.authorize(signedIn, parseTableState({}, finesTable))).toBe(true);
    const exported = await fines.rows(parseTableState({}, finesTable), null, { limit: 100, viewer: signedIn });
    expect((exported.rows as { fine_id: number }[]).map((f) => f.fine_id)).toEqual(all.rows.map((f) => f.fine_id));
    expect((await fines.rows(parseTableState(new URLSearchParams('f=account:in:104'), finesTable), null, { limit: 100, viewer: signedIn })).rows).toEqual([]);
    vi.mocked(getViewer).mockResolvedValue(viewer(DAVE, 'government'));
    expect(await fines.authorize(signedIn, parseTableState({}, finesTable))).toBe(false);
    vi.mocked(getViewer).mockResolvedValue(official);
  });

  it('audit: ledger feed and movers within scope, keyset pages, export', async () => {
    const page1 = await getLedgerAudit(scope, ids, RANGE, parseTableState(new URLSearchParams('limit=3'), govLedgerTable));
    expect(page1.rows).toHaveLength(3);
    expect(page1.next).not.toBeNull();
    const page2 = await getLedgerAudit(scope, ids, RANGE, parseTableState(new URLSearchParams(`limit=3&cursor=${encodeURIComponent((await import('@/lib/pagination/cursor')).encodeCursor(page1.next!))}`), govLedgerTable));
    expect(page2.rows[0]!.txn_id).toBeLessThan(page1.rows[2]!.txn_id);
    // The `gov-transactions` export, in keyset batches, with the range as a `settled` chip.
    const ledger = getExport('gov-transactions')!;
    const signedIn = official as Extract<Viewer, { anon: false }>;
    const exportState = parseTableState(new URLSearchParams(`f=settled:between:${RANGE.from},${RANGE.to}`), govLedgerExportTable);
    const everything: { account_id: number; txn_id: number }[] = [];
    let cursor: unknown = null;
    do {
      const batch = await ledger.rows(exportState, cursor, { limit: 5, viewer: signedIn });
      everything.push(...(batch.rows as { account_id: number; txn_id: number }[]));
      cursor = batch.next;
    } while (cursor);
    expect(accountsOf(everything)).toEqual(IN_SCOPE);
    expect(everything).toHaveLength(12);
    vi.mocked(getViewer).mockResolvedValue(moderator); // government.read.all, but no government.audit.read
    expect(await ledger.authorize(signedIn, exportState)).toBe(false);
    vi.mocked(getViewer).mockResolvedValue(official);
    const out = await getLedgerAudit(scope, ids, RANGE, parseTableState(new URLSearchParams(`f=direction:in:out&f=initiator:in:${OFFICIAL}`), govLedgerTable));
    expect(out.rows.map((m) => [m.account_id, m.amount, m.initiator_name])).toEqual([[102, '-200.00', 'Official']]);
    expect(out.labels).toEqual({ [`initiator:${OFFICIAL}`]: 'Official' });
    expect((await getLedgerAudit(scope, ids, RANGE, parseTableState(new URLSearchParams('f=account:in:104'), govLedgerTable))).rows).toEqual([]);
  });

  it('audit: explorer views of in-scope accounts and /gov pages, never other targets', async () => {
    const v = await getViewsAudit(scope, ids, RANGE, parseTableState({}, govViewsTable));
    expect(v.rows.map((r) => r.path)).toEqual(['/gov', '/accounts/102']);
    const filtered = await getViewsAudit(scope, ids, RANGE, parseTableState(new URLSearchParams(`f=actor:in:${CAROL}`), govViewsTable));
    expect(filtered.rows.map((r) => r.actor_name)).toEqual(['Carol']);
  });

  it('monthly report: opening and closing balances and the fines summary', async () => {
    const month = `${D1.slice(0, 7)}-01`;
    const r = await getMonthlyReport(scope, ids, month);
    expect(r.closing).not.toBeNull();
    expect(r.flows.coverage.covered).toBe(true);
    // Fixture days may straddle a month boundary; the report only counts its month.
    if (D2.startsWith(D1.slice(0, 7))) expect(r.fines).toMatchObject({ count: 1, total: '40.00', revoked: 0 });
  });
});

d('compliance', () => {
  const compliance = viewer(CAROL, 'compliance') as ComplianceViewer;

  it('investigates a firm: accounts, counterparties, proprietor transfers and flags', async () => {
    await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name) VALUES (110, 'BUSINESS', UNHEX(${hex(ALICE)}), 'Acme Savings')`.execute(db);
    await sql`INSERT INTO account_balances_mat (account_id, balance) VALUES (110, 0)`.execute(db);
    await sql`INSERT INTO firm_accounts (firm_id, account_id) VALUES (1, 110)`.execute(db);
    await txn(`${D1} 15:00:00`, 'BusinessPlugin', 'owner draw', [[110, '-5000.00'], [1, '5000.00']]);
    await recomputeDay(rollupDb(), 'account_day', D1);
    await recomputeDay(rollupDb(), 'pair_month', D1);
    resetCacheForTests();
    const f = await getFirmInvestigation(compliance, 1, RANGE);
    expect(f?.firm.display_name).toBe('Acme Corp');
    expect(f?.accounts.map((a) => a.account_id).sort()).toEqual([110, 3]);
    expect(f?.proprietor.toProprietor).toBe('5000.00');
    expect(f?.flagged.rows.map((m) => [m.account_id, m.amount, m.large, m.round])).toEqual([[110, '-5000.00', false, true]]);
    expect(f?.counterparties.incoming.map((c) => c.counterparty_id)).toContain(102);
    expect(await getFirmInvestigation(compliance, 99999, RANGE)).toBeNull();
  });

  it('keeps a per-viewer watchlist and shows recent activity on it', async () => {
    vi.mocked(getViewer).mockResolvedValue(compliance);
    expect(await setWatched(110, true)).toEqual({ ok: true });
    expect(await setWatched(99999, true)).toMatchObject({ ok: false });
    expect((await listWatchlist(CAROL)).map((w) => w.account_id)).toEqual([110]);
    expect(await listWatchlist(ALICE)).toEqual([]);
    const home = await getComplianceHome(compliance);
    expect(home.watched[0]).toMatchObject({ account_id: 110, outflow7: '5000.00', firm_name: 'Acme Corp' });
    expect(home.recent.map((m) => m.account_id)).toEqual([110]);
    vi.mocked(getViewer).mockResolvedValue(official);
    expect(await setWatched(110, false)).toMatchObject({ ok: false });
    vi.mocked(getViewer).mockResolvedValue(compliance);
    expect(await setWatched(110, false)).toEqual({ ok: true });
  });
});

d('gov unit administration', () => {
  it('needs gov_units.manage', async () => {
    vi.mocked(getViewer).mockResolvedValue(moderator);
    expect(await getGovUnitsAdmin()).toBeNull();
    expect(await saveGovUnit(null, { slug: 'x', name: 'X', parentUnitId: null, description: null })).toMatchObject({ ok: false });
  });

  it('creates, nests, assigns, moves and deletes units', async () => {
    vi.mocked(getViewer).mockResolvedValue(admin);
    const created = await saveGovUnit(null, { slug: 'justice', name: 'Justice', parentUnitId: 3, description: 'Courts' });
    if (!created.ok || created.id === undefined) throw new Error('create failed');
    expect(await saveGovUnit(null, { slug: 'justice', name: 'Again', parentUnitId: null, description: null })).toMatchObject({ ok: false });
    expect(await saveGovUnit(3, { slug: 'treasury', name: 'Treasury', parentUnitId: created.id, description: null })).toMatchObject({ ok: false });
    expect(await setAccountUnit(105, created.id)).toEqual({ ok: true });
    expect(await setAccountUnit(1, created.id)).toMatchObject({ ok: false }); // personal account
    expect(await setAccountUnit(106, created.id)).toMatchObject({ ok: false }); // archived
    expect(await setAccountUnit(105, 1)).toEqual({ ok: true }); // moves, never in two units
    const data = await getGovUnitsAdmin();
    expect(data?.accounts.find((a) => a.account_id === 105)?.unit_id).toBe(1);
    expect(data?.units.find((u) => u.slug === 'justice')).toMatchObject({ parent_name: 'Treasury', accounts: 0 });
    expect(await removeGovUnit(created.id)).toEqual({ ok: true });
    expect(await setAccountUnit(105, null)).toEqual({ ok: true });
    expect((await getGovUnitsAdmin())?.units.map((u) => u.slug)).toEqual(['commerce', 'trade', 'treasury']);
    const audited = await sql<{ n: number }>`SELECT COUNT(*) AS n FROM explorer_audit WHERE path = '/admin/gov-units' AND method <> 'GET'`.execute(db);
    expect(Number(audited.rows[0]?.n)).toBeGreaterThanOrEqual(5);
  });
});

