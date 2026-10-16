import { sql } from 'kysely';
import { db, uuidToBin } from '@/lib/db';
import { getViewer } from '@/lib/auth/viewer';
import { createRole, updateRole, deleteRole, setDirectRoleMember, type RoleInput } from '@/lib/services/role';
import { setGroupCapabilities, addManualMember, setLuckpermsNode, deleteGroup, removeManualMember } from '@/lib/services/group';
import { resolveAuthorization, listRoleDefinitions, permissionsForRoles } from '@/lib/sql/role';
vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));
import { permissionsFor } from '../role-fixtures';
import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest';
import { HAS_DB, resetDb, testDbOptions, ALICE, BOB, CAROL, DAVE, BEDROCK, SECRETARY, PENNY } from './db';
import { accountLabel, looksLikeUuid } from '@/lib/format';
import { findAccount, findPostingsByTxnId, getTotalSupply, getPersonalSupply, canReadAccount } from '@/lib/sql/ledger';
import { getPersonalBalances, getBalanceDistribution } from '@/lib/sql/stats';
import { isFirmMember, hasFirmFinancialAccess, getAccountFirmId, getFirmStats, findFirmByDisplayName } from '@/lib/sql/firm';
import { listFinanceFirms } from '@/lib/sql/webhook';
import { findCapabilities, findPlayerUuidByName } from '@/lib/sql/group';
import { findAccountsForPlayer, getPlayerTotals } from '@/lib/sql/me';
import { findIdentityBySub } from '@/lib/sql/identity';
import { getMoneyFlow } from '@/lib/sql/moneyFlow';
import { listItemSales, getMarketFirm, listFirmMarketItems } from '@/lib/sql/market';
import mysql from 'mysql2/promise';
import { gini } from '@/lib/derived';
import { audit } from '@/lib/audit';
import { listAudit } from '@/lib/sql/audit';
import { findConfigByPrefix } from '@/lib/sql/economyConfig';
import { parseTaxRates } from '@/lib/taxRates';

// Real queries against a real MariaDB (no mocking). Skipped unless RUN_INTEGRATION=1.
const d = HAS_DB ? describe : describe.skip;

/**
 * Record a fine the way GovServiceImpl does: each fine points at its own debit
 * txn (V29's UNIQUE(txn_id) — one fine per debit). The debit's postings are left
 * out so the seed's balance assertions stay put; the fine queries only read the
 * txn header. Returns the new fine_id.
 */
async function issueFine(fine: { reason: string; amount: number; payer?: string; govAccountId?: number; revoked?: boolean }): Promise<number> {
  const txn = await sql`INSERT INTO ledger_txns (message, initiator_uuid_bin, plugin_system)
    VALUES (${'Fine: ' + fine.reason}, ${uuidToBin(ALICE)}, 'treasury')`.execute(db);
  const row = await sql`INSERT INTO government_fines (player_uuid_bin, gov_account_id, amount, reason, txn_id, issued_by_uuid_bin, revoked)
    VALUES (${uuidToBin(fine.payer ?? BOB)}, ${fine.govAccountId ?? 5}, ${fine.amount}, ${fine.reason}, ${txn.insertId}, ${uuidToBin(ALICE)}, ${fine.revoked ? 1 : 0})`.execute(db);
  return Number(row.insertId);
}

/** Remove fines issued by issueFine, then their debit txns (fk_fines_txn is RESTRICT). */
async function deleteFines(reason: string): Promise<void> {
  const txns = await sql<{ txn_id: number }>`SELECT txn_id FROM government_fines WHERE reason = ${reason}`.execute(db);
  await sql`DELETE FROM government_fines WHERE reason = ${reason}`.execute(db);
  for (const { txn_id } of txns.rows) await sql`DELETE FROM ledger_txns WHERE txn_id = ${txn_id}`.execute(db);
}

beforeAll(async () => {
  if (HAS_DB) await resetDb();
}, 60_000);

d('account name resolution', () => {
  it('resolves a personal account whose display_name is its UUID to the player name', async () => {
    const a = await findAccount(1); // Alice, display_name = uuid
    expect(a).not.toBeNull();
    expect(looksLikeUuid(a!.display_name)).toBe(true);
    expect(a!.owner_name).toBe('Alice');
    expect(accountLabel(a!)).toBe('Alice');
    expect(a!.balance).toBe('5000.00');
  });

  it('keeps a real business display name', async () => {
    const a = await findAccount(3);
    expect(accountLabel(a!)).toBe('Acme Corp');
  });

  it('falls back to a short UUID for a player not in economy_players (Dave)', async () => {
    const a = await findAccount(7);
    expect(a!.owner_name).toBeNull();
    expect(accountLabel(a!)).toMatch(/…$/); // shortened UUID
    expect(looksLikeUuid(accountLabel(a!))).toBe(false);
  });
});

d('transaction postings resolve names (the HIGH bug fix)', () => {
  it('never renders a raw UUID for a posting account', async () => {
    const postings = await findPostingsByTxnId(1); // Alice personal -> Acme business
    expect(postings).toHaveLength(2);
    const labels = postings.map((p) => accountLabel(p));
    expect(labels).toContain('Alice');
    expect(labels).toContain('Acme Corp');
    for (const l of labels) expect(looksLikeUuid(l)).toBe(false);
  });
});

d('supply, balances, gini, distribution', () => {
  it('excludes SYSTEM and archived from supply', async () => {
    expect(await getPersonalSupply()).toBe('106500.00'); // 5000 + 100000 + 1500
    expect(await getTotalSupply()).toBe('139500.00'); // + business 25000 + gov 8000, minus SYSTEM
  });

  it('returns only positive active personal balances and a sensible gini', async () => {
    const balances = (await getPersonalBalances()).sort((a, b) => a - b);
    expect(balances).toEqual([1500, 5000, 100000]);
    expect(gini(balances)).toBeGreaterThan(0.5); // one rich account dominates
  });

  it('buckets balances into the finer bands', async () => {
    const dist = await getBalanceDistribution();
    const count = (k: string) => dist.find((b) => b.bucket === k)?.account_count ?? 0;
    expect(count('1k_2k5')).toBe(1); // 1500 (1K–2.5K)
    expect(count('5k_10k')).toBe(1); // 5000 lands here (boundary is balance < 5000)
    expect(count('100k_plus')).toBe(1); // 100000
    expect(dist.every((b) => typeof b.bucket_label === 'string' && b.bucket_label.length > 0)).toBe(true);
  });
});

d('firm financial-access tiers', () => {
  it('isFirmMember for current employees only', async () => {
    expect(await isFirmMember(1, ALICE)).toBe(true);
    expect(await isFirmMember(1, BOB)).toBe(true);
    expect(await isFirmMember(1, DAVE)).toBe(false);
  });

  it('hasFirmFinancialAccess requires the FINANCIAL/ADMIN role permission', async () => {
    expect(await hasFirmFinancialAccess(1, ALICE)).toBe(true); // Owner: ADMIN
    expect(await hasFirmFinancialAccess(1, BOB)).toBe(true); // Finance: FINANCIAL
    expect(await hasFirmFinancialAccess(1, CAROL)).toBe(false); // Worker: DEFAULT only
    expect(await hasFirmFinancialAccess(1, DAVE)).toBe(false); // not an employee, not the owner
  });

  // The proprietor is implicitly all-powerful but is NOT stored as a
  // firm_employee row (the plugin tracks them only via firm.proprietor_uuid_bin
  // and short-circuits isProprietor in hasPermission). ~39% of prod firms have
  // such an employee-row-less owner; an employee-only gate wrongly locked them
  // out of their own firm's books and webhook dropdown. TaxFree Co (firm 2) is
  // proprietored by DAVE with zero employees — the canonical case.
  it('hasFirmFinancialAccess grants the proprietor even with no firm_employee row', async () => {
    expect(await hasFirmFinancialAccess(2, DAVE)).toBe(true); // owner of TaxFree Co, no employee row
    expect(await hasFirmFinancialAccess(2, ALICE)).toBe(false); // neither owner nor employee of firm 2
  });

  // The webhook scope dropdown (lib/sql/webhook.listFinanceFirms) must match the
  // financial-access gate exactly, including owners with no employee row.
  it('listFinanceFirms returns owned + FINANCIAL/ADMIN-employed firms (deduped)', async () => {
    const names = async (uuid: string) => (await listFinanceFirms(uuid)).map((f) => f.displayName);
    expect(await names(ALICE)).toEqual(['Acme Corp']); // owner + ADMIN employee → one row
    expect(await names(BOB)).toEqual(['Acme Corp']); // FINANCIAL employee
    expect(await names(CAROL)).toEqual([]); // Worker: DEFAULT only
    expect(await names(DAVE)).toEqual(['TaxFree Co']); // proprietor, no employee row
  });

  it('maps firm accounts and reports firm stats', async () => {
    expect(await getAccountFirmId(3)).toBe(1); // Acme business account
    expect(await getAccountFirmId(1)).toBeNull(); // personal account
    const s = await getFirmStats();
    expect(s.businessWealth).toBe('25000.00'); // only Acme has a business account
    expect(s.activeFirms).toBe(2); // Acme + TaxFree Co
    expect(s.activeEmployees).toBe(3); // TaxFree Co has no employees
    expect(s.newThisMonth).toBe(2);
    expect(s.avgEmployees).toBeCloseTo(1.5, 5); // 3 employees / 2 firms
  });

  it('surfaces balance-tax exemption, ignoring soft-deleted flags', async () => {
    const exempt = await findFirmByDisplayName('TaxFree Co');
    expect(exempt?.exempt).toBe(true);

    // Acme's exempt flag is soft-deleted (deleted_at set) → must read as not exempt.
    const acme = await findFirmByDisplayName('Acme Corp');
    expect(acme?.exempt).toBe(false);
  });

  it('resolves the proprietor name, falling back to null when uncached (PAR-208)', async () => {
    // Acme Corp's proprietor (Alice) is in economy_players → name resolves.
    const acme = await findFirmByDisplayName('Acme Corp');
    expect(acme?.proprietor_uuid).toBe('00000000-0000-0000-0000-00000000a1ce');
    expect(acme?.proprietor_name).toBe('Alice');

    // TaxFree Co's proprietor (DAVE) is absent from economy_players → name is null
    // (the page renders the short UUID via <Player/>).
    const taxFree = await findFirmByDisplayName('TaxFree Co');
    expect(taxFree?.proprietor_uuid).toBe('00000000-0000-0000-0000-00000000da7e');
    expect(taxFree?.proprietor_name).toBeNull();
  });
});

d('money flow counts only clean cross-type transfers', () => {
  it('includes the 2-leg cross-type txn and excludes same-type + multi-leg', async () => {
    const edges = await getMoneyFlow(30);
    expect(edges).toHaveLength(1); // txn2 is same-type, txn3 is 3-leg
    expect(edges[0]).toMatchObject({ from_type: 'PERSONAL', to_type: 'BUSINESS', txn_count: 1 });
    expect(edges[0]?.amount).toBe('500.00');
  });
});

d('group capabilities', () => {
  it('returns the player role for a manual group member', async () => {
    // Bob is a manual member of the "Players" group (grants player).
    expect(await findCapabilities(BOB)).toContain('player');
  });

  it('returns the player role for a LuckPerms-synced member (the recon-cron path)', async () => {
    // Carol holds her membership via source='luckperms' — i.e. the reconciliation
    // cron synced her from the group's node. The same capability must resolve, so
    // a linked player gets the player role purely from their in-game group.
    expect(await findCapabilities(CAROL)).toContain('player');
  });

  it('returns nothing for a player in no groups', async () => {
    // The government-account secretary has account_access but no explorer_group.
    expect(await findCapabilities(SECRETARY)).toEqual([]);
  });
});

d('audit logging records privileged access', () => {
  it('persists an audit row that listAudit reads back', async () => {
    await audit({
      viewer: { anon: false, keycloakSub: 'e2e-admin', minecraftUuid: ALICE, minecraftName: 'Alice', linked: true, role: 'admin', capabilities: ['player', 'admin'], permissions: permissionsFor('player', 'admin') },
      method: 'GET',
      path: '/transactions',
      targetType: 'global',
      targetId: null,
      outcome: 200,
      sourceIp: '203.0.113.7',
    });
    const rows = await listAudit({ actor: null, targetType: null, limit: 50, offset: 0 });
    const row = rows.find((r) => r.path === '/transactions');
    expect(row).toBeTruthy();
    expect(row!.actor_role).toBe('admin');
  });
});

d('market seller names resolve (no UUIDs)', () => {
  it('shows player and firm names, never a raw UUID', async () => {
    const sales = await listItemSales('DIAMOND', 50, 0);
    expect(sales.length).toBe(2);
    const names = sales.map((s) => s.owner_name);
    expect(names).toContain('Alice'); // personal shop, display_name was a UUID
    expect(names).toContain('Acme Corp'); // firm shop
    for (const n of names) expect(looksLikeUuid(n)).toBe(false);
  });
});

// The in-game `/firm sales export <firm> <days>` link opens
// /chestshop/firms/<id>?days=N; the firm drilldown must honour the window.
d('firm market drilldown honours the ?days window', () => {
  beforeAll(async () => {
    // One Acme (firm 1) sale 40 days old, alongside the two seeded "now" sales,
    // and a 40-day-old sale that is TaxFree Co's (firm 2) only one.
    const conn = await mysql.createConnection(testDbOptions());
    try {
      await conn.query(
        `INSERT INTO chestshop_sale (txn_id, occurred_at, direction, customer_uuid_bin, shop_account_id, shop_account_type, shop_firm_id, shop_owner_uuid_bin, is_admin_shop, material, item_key, item_name, item_custom, quantity, unit_price, total_price)
         VALUES (NULL, NOW() - INTERVAL 40 DAY, 'SELL', UNHEX('00000000000000000000000000000B0B'), 3, 'BUSINESS', 1, UNHEX('0000000000000000000000000000A1CE'), 0, 'GOLD_INGOT', 'GOLD_INGOT', 'Gold Ingot', 0, 4, 25.0000, 100.00),
                (NULL, NOW() - INTERVAL 40 DAY, 'SELL', UNHEX('00000000000000000000000000000B0B'), NULL, 'BUSINESS', 2, UNHEX('0000000000000000000000000000DA7E'), 0, 'GOLD_INGOT', 'GOLD_INGOT', 'Gold Ingot', 0, 1, 25.0000, 25.00)`,
      );
    } finally {
      await conn.end();
    }
  });

  it('all-time (no window) includes the old sale', async () => {
    const firm = await getMarketFirm(1);
    expect(firm).toMatchObject({ firm_id: 1, display_name: 'Acme Corp', sale_count: 3, total_quantity: 29 });
    expect(Number(firm!.total_volume)).toBe(150);
    const items = await listFirmMarketItems(1, 50, 0);
    expect(items.map((i) => i.item_key).sort()).toEqual(['DIAMOND', 'GOLD_INGOT', 'IRON_INGOT']);
  });

  it('a 30-day window excludes the 40-day-old sale from totals and items', async () => {
    const firm = await getMarketFirm(1, 30);
    expect(firm).toMatchObject({ firm_id: 1, display_name: 'Acme Corp', sale_count: 2, total_quantity: 25 });
    expect(Number(firm!.total_volume)).toBe(50);
    const items = await listFirmMarketItems(1, 50, 0, 30);
    expect(items.map((i) => i.item_key).sort()).toEqual(['DIAMOND', 'IRON_INGOT']);
  });

  it('a wider window brings it back', async () => {
    expect((await getMarketFirm(1, 60))!.sale_count).toBe(3);
    expect(await listFirmMarketItems(1, 50, 0, 60)).toHaveLength(3);
  });

  it('still resolves a firm with no sales inside the window (zero totals, not a 404)', async () => {
    // TaxFree Co (firm 2): its only sale is 40 days old.
    const windowed = await getMarketFirm(2, 30);
    expect(windowed).toMatchObject({ firm_id: 2, display_name: 'TaxFree Co', sale_count: 0, total_quantity: 0 });
    expect(Number(windowed!.total_volume)).toBe(0);
    expect(await listFirmMarketItems(2, 50, 0, 30)).toEqual([]);
    expect((await getMarketFirm(2))!.sale_count).toBe(1);
  });

  it('returns null for a firm with no ChestShop sales at all', async () => {
    expect(await getMarketFirm(999_999, 30)).toBeNull();
    expect(await getMarketFirm(999_999)).toBeNull();
  });
});

// PAR-240: a Bedrock/Floodgate player who completes the link must reach a working,
// correctly-named wallet. The flow already binds the in-game (Floodgate) UUID; this
// locks in that the explorer's DAL resolves it end to end.
d('Bedrock / Floodgate linked wallet (PAR-240)', () => {
  it('resolves the durable identity to the Floodgate UUID + dotted name', async () => {
    const id = await findIdentityBySub('e2e-bedrock');
    expect(id).not.toBeNull();
    expect(id!.playerUuid).toBe(BEDROCK);
    expect(id!.minecraftName).toBe('.BedrockBob');
  });

  it('resolves the wallet with the dotted name, never a bare UUID', async () => {
    const a = (await findAccountsForPlayer(BEDROCK)).find((x) => x.account_id === 8);
    expect(a).toBeTruthy();
    expect(a!.owner_name).toBe('.BedrockBob');
    expect(accountLabel(a!)).toBe('.BedrockBob');
    expect(looksLikeUuid(accountLabel(a!))).toBe(false);
  });

  it('finds a Floodgate player by their dotted name (case-insensitive)', async () => {
    expect(await findPlayerUuidByName('.BedrockBob')).toBe(BEDROCK);
    expect(await findPlayerUuidByName('.BEDROCKBOB')).toBe(BEDROCK);
  });
});

// PAR-237: a government department "secretary" is a read-only viewer of their
// department account. The explorer must let them see that account's ledger
// history (scoped to the account, never blanket), so canReadAccount drives the
// account-detail history gate.
d('government account viewer access (PAR-237)', () => {
  it('grants a read-only viewer access to their department account', async () => {
    expect(await canReadAccount(5, SECRETARY)).toBe(true); // City Hall (GOVERNMENT)
  });

  it('does not grant access to an unrelated account', async () => {
    expect(await canReadAccount(1, SECRETARY)).toBe(false); // Alice's personal account
  });

  it('denies a player with no member/authorizer/viewer row', async () => {
    expect(await canReadAccount(5, DAVE)).toBe(false);
  });

  // ADT-13: the "my accounts" list must agree with canReadAccount. Previously it
  // filtered MEMBER/AUTHORIZER, so a viewer could open the account page but the
  // account never showed in their own list. Both now share account_read_access_web.
  it('lists the viewer-granted department account under "my accounts" (ADT-13)', async () => {
    const ids = (await findAccountsForPlayer(SECRETARY)).map((a) => a.account_id);
    expect(ids).toContain(5);
  });
});

// behaviour/0001: the dashboard KPI rollups (total balance, income, spend, net)
// must be summed in SQL and carried as exact DECIMAL strings, never folded through
// a JS double. Penny's fixture (account #9) is chosen so the windowed credit and
// debit totals do NOT round-trip through IEEE-754: 0.10+0.20+0.30 and
// 0.15+0.25+0.20 both reduce to 0.6000000000000001 in a double, but the exact
// DECIMAL sum is 0.60. If getPlayerTotals summed in JS the strings below would
// carry that long tail; SQL SUM gives the exact ledger figure.
d('player KPI rollups sum money in SQL, exact to the cent (behaviour/0001)', () => {
  it('returns exact DECIMAL strings, not double-drifted numbers', async () => {
    const ids = (await findAccountsForPlayer(PENNY)).map((a) => a.account_id).sort((a, b) => a - b);
    expect(ids).toEqual([9]);

    const t = await getPlayerTotals(ids, 90);
    // Exact strings — the very values a JS-double reduce would corrupt.
    expect(t.totalBalance).toBe('0.00'); // Penny's wallet nets to zero
    expect(t.income).toBe('0.60');       // 0.10 + 0.20 + 0.30 (double → 0.6000000000000001)
    expect(t.spend).toBe('0.60');        // 0.15 + 0.25 + 0.20 (double → 0.6000000000000001)
    expect(t.net).toBe('0.00');          // SUM(amount) over the window

    // Guard: the JS-double reduce the component used to do drifts off the exact
    // string (that IS the finding). The SQL path is unaffected.
    expect(String([0.1, 0.2, 0.3].reduce((s, x) => s + x, 0))).not.toBe('0.6'); // 0.6000000000000001
    expect(t.income).toBe('0.60');
  });

  it('is empty-safe for a player with no accounts', async () => {
    const t = await getPlayerTotals([], 90);
    expect(t).toEqual({ totalBalance: '0.00', income: '0.00', spend: '0.00', net: '0.00' });
  });
});

// Regression coverage for the shared WEB relationship view and scoped government SQL.
d('RBAC relationship snapshots', () => {
  async function connect() {
    return mysql.createConnection(testDbOptions());
  }
  it('LP groups grant account reads and My data, expire, and respect removal', async () => {
    const c = await connect();
    try {
      await c.query("INSERT INTO account_group_access (account_id, lp_group, level, added_by_uuid_bin) VALUES (5, 'rbac-test', 'VIEWER', UNHEX(REPLACE(?, '-', '')))", [ALICE]);
      await c.query("INSERT INTO explorer_lp_group_member (lp_group, player_uuid_bin) VALUES ('rbac-test', UNHEX(REPLACE(?, '-', '')))", [DAVE]);
      expect(await canReadAccount(5, DAVE)).toBe(true);
      expect(await canReadAccount(1, DAVE)).toBe(false);
      expect((await findAccountsForPlayer(DAVE)).map(a => a.account_id)).toContain(5);
      await c.query("UPDATE explorer_lp_group_member SET synced_at = NOW() - INTERVAL 2 HOUR WHERE lp_group = 'rbac-test'");
      expect(await canReadAccount(5, DAVE)).toBe(false);
      expect((await findAccountsForPlayer(DAVE)).map(a => a.account_id)).not.toContain(5);
      await c.query("UPDATE explorer_lp_group_member SET synced_at = NOW() WHERE lp_group = 'rbac-test'");
      await c.query("UPDATE account_group_access SET removed_at = NOW() WHERE lp_group = 'rbac-test'");
      expect(await canReadAccount(5, DAVE)).toBe(false);
    } finally {
      await c.query("DELETE FROM account_group_access WHERE lp_group = 'rbac-test'");
      await c.query("DELETE FROM explorer_lp_group_member WHERE lp_group = 'rbac-test'");
      await c.end();
    }
  });
  it('firm finance access agrees across detail pages and My data', async () => {
    expect(await canReadAccount(3, BOB)).toBe(true);
    expect((await findAccountsForPlayer(BOB)).map(a => a.account_id)).toContain(3);
    expect(await canReadAccount(3, CAROL)).toBe(false);
  });
  it('expired role sync revokes roles while manual grants survive', async () => {
    const c = await connect();
    try {
      await c.query("UPDATE explorer_group_member SET synced_at = NOW() - INTERVAL 2 HOUR WHERE source = 'luckperms'");
      expect(await findCapabilities(CAROL)).toEqual([]);
      expect(await findCapabilities(BOB)).toContain('player');
    } finally {
      await c.query("UPDATE explorer_group_member SET synced_at = NOW() WHERE source = 'luckperms'");
      await c.end();
    }
  });
  it('deleted firm roles cannot retain financial access', async () => {
    const c = await connect();
    try {
      await c.query("UPDATE firm_role SET deleted_at = NOW() WHERE name = 'Finance'");
      expect(await hasFirmFinancialAccess(1, BOB)).toBe(false);
      expect(await canReadAccount(3, BOB)).toBe(false);
      expect((await findAccountsForPlayer(BOB)).map(a => a.account_id)).not.toContain(3);
    } finally {
      await c.query("UPDATE firm_role SET deleted_at = NULL WHERE name = 'Finance'");
      await c.end();
    }
  });
  it('government queries filter both details and aggregate totals', async () => {
    const { getGovernmentAccounts, getRecentFines, getFineCategorySummary } = await import('@/lib/sql/government');
    expect((await getGovernmentAccounts({ playerUuid: SECRETARY })).map(a => a.account_id)).toEqual([5]);
    expect(await getGovernmentAccounts({ playerUuid: DAVE })).toEqual([]);
    try {
      await issueFine({ reason: 'rbac-test', amount: 100 });
      expect(await getRecentFines(30, 100, [])).toEqual([]);
      expect(await getFineCategorySummary(30, [])).toEqual([]);
      expect((await getRecentFines(30, 100, [5])).some(f => f.reason === 'rbac-test')).toBe(true);
      expect((await getFineCategorySummary(30, [5])).find(f => f.category === 'rbac-test')?.total_amount).toBe('100.00');
    } finally {
      await deleteFines('rbac-test');
    }
  });
});

d('configurable authorization policy', () => {
  beforeEach(async () => {
    await resetDb();
    await sql`INSERT INTO explorer_role (player_uuid_bin, role) VALUES (${uuidToBin(ALICE)}, 'admin')`.execute(db);
    vi.mocked(getViewer).mockResolvedValue({ anon: false, keycloakSub: 'test-policy-admin', minecraftUuid: ALICE, minecraftName: 'Alice', linked: true, role: 'admin', capabilities: ['player', 'admin'], permissions: permissionsFor('admin') });
  });
  const custom: RoleInput = { key: 'auditor', name: 'Account auditor', description: 'Scoped custom role', permissions: ['account.read.all'] };
  it('seeds exactly the permission catalogue for admin and the documented defaults', async () => {
    const roles = await listRoleDefinitions();
    for (const key of ['player', 'government', 'moderator', 'admin']) {
      expect(roles.find(r => r.key === key)?.permissions.sort()).toEqual(permissionsFor(key).sort());
    }
  });
  it('creates and assigns a custom role, then applies edited permissions on the next resolution', async () => {
    await createRole(custom);
    await setDirectRoleMember('auditor', BOB, true);
    expect((await resolveAuthorization(BOB)).permissions).toContain('account.read.all');
    await updateRole({ ...custom, permissions: ['api_keys.read'] }, 1);
    const next = await resolveAuthorization(BOB);
    expect(next.roles).toContain('auditor');
    expect(next.permissions).toContain('api_keys.read');
    expect(next.permissions).not.toContain('account.read.all');
    await setDirectRoleMember('auditor', BOB, false);
    expect((await resolveAuthorization(BOB)).roles).not.toContain('auditor');
  });
  it('resolves custom roles through fresh LP membership and revokes stale leases', async () => {
    await createRole(custom);
    await sql`INSERT INTO explorer_group (group_id, name, luckperms_node) VALUES (99, 'Auditors', 'group.auditor')`.execute(db);
    await sql`INSERT INTO explorer_group_capability VALUES (99, 'auditor')`.execute(db);
    await sql`INSERT INTO explorer_group_member (group_id, player_uuid_bin, source, synced_at) VALUES (99, ${uuidToBin(BOB)}, 'luckperms', CURRENT_TIMESTAMP)`.execute(db);
    expect((await resolveAuthorization(BOB)).permissions).toContain('account.read.all');
    await sql`UPDATE explorer_group_member SET synced_at = CURRENT_TIMESTAMP - INTERVAL 2 HOUR WHERE group_id = 99`.execute(db);
    expect((await resolveAuthorization(BOB)).permissions).not.toContain('account.read.all');
    await addManualMember(99, BOB, ALICE);
    expect((await resolveAuthorization(BOB)).permissions).toContain('account.read.all');
  });
  it('deletes direct and group grants so recreating a role cannot resurrect them', async () => {
    await createRole(custom);
    await setDirectRoleMember('auditor', BOB, true);
    await sql`INSERT INTO explorer_group (group_id, name) VALUES (99, 'Auditors')`.execute(db);
    await setGroupCapabilities(99, ['auditor']);
    await addManualMember(99, CAROL, ALICE);
    expect((await resolveAuthorization(CAROL)).roles).toContain('auditor');
    await deleteRole('auditor', 1);
    await createRole(custom);
    expect((await resolveAuthorization(BOB)).roles).not.toContain('auditor');
    expect((await resolveAuthorization(CAROL)).roles).not.toContain('auditor');
  });
  it('edits the authenticated baseline without any implicit permission fallback', async () => {
    await updateRole({ key: 'player', name: 'Player', description: null, permissions: [] }, 1);
    expect((await resolveAuthorization(null)).permissions).toEqual([]);
    expect((await resolveAuthorization(DAVE)).permissions).toEqual([]);
    await expect(deleteRole('player', 2)).rejects.toThrow('baseline');
    await expect(deleteRole('PLAYER', 2)).rejects.toThrow();
  });
  it('rejects stale edits and deletes without losing the newer permission set', async () => {
    await createRole(custom);
    await updateRole({ ...custom, permissions: ['api_keys.read'] }, 1);
    await expect(updateRole(custom, 1)).rejects.toThrow('Refresh');
    await expect(deleteRole('auditor', 1)).rejects.toThrow('Refresh');
    expect((await listRoleDefinitions()).find(r => r.key === 'auditor')?.permissions).toEqual(['api_keys.read']);
  });
  it('rolls back removal of the operator’s own management permission', async () => {
    const admin = (await listRoleDefinitions()).find(r => r.key === 'admin')!;
    await expect(updateRole({ ...admin, permissions: admin.permissions.filter(p => p !== 'roles.update') }, 1)).rejects.toThrow('own access');
    expect((await resolveAuthorization(ALICE)).permissions).toContain('roles.update');
    await expect(deleteRole('admin', 1)).rejects.toThrow('own access');
    await expect(setDirectRoleMember('admin', ALICE, false)).rejects.toThrow('last role-assignment');
    expect((await resolveAuthorization(ALICE)).roles).toContain('admin');
  });
  it('delegated managers cannot grant or modify more powerful roles through any assignment path', async () => {
    await createRole({ key: 'delegator', name: 'Delegator', description: null, permissions: ['roles.create', 'roles.update', 'roles.delete', 'roles.assign', 'groups.roles.update', 'groups.members.update', 'groups.sync.update', 'groups.delete'] });
    await sql`INSERT INTO explorer_group (group_id, name) VALUES (99, 'Admins')`.execute(db);
    await setGroupCapabilities(99, ['admin']);
    await setDirectRoleMember('delegator', BOB, true);
    vi.mocked(getViewer).mockResolvedValue({ anon: false, keycloakSub: 'delegate', minecraftUuid: BOB, minecraftName: 'Bob', linked: true, role: 'delegator', capabilities: ['player', 'delegator'], permissions: await permissionsForRoles(['player', 'delegator']) });
    await expect(createRole(custom)).rejects.toThrow('permissions you hold');
    await expect(updateRole({ key: 'admin', name: 'Admin', description: null, permissions: [] }, 1)).rejects.toThrow('do not hold');
    await expect(deleteRole('admin', 1)).rejects.toThrow('do not hold');
    await expect(setDirectRoleMember('admin', BOB, true)).rejects.toThrow('do not hold');
    await expect(addManualMember(99, BOB, BOB)).rejects.toThrow('do not hold');
    await expect(setLuckpermsNode(99, 'group.everyone')).rejects.toThrow('do not hold');
    await expect(setGroupCapabilities(99, [])).rejects.toThrow('do not hold');
    await expect(deleteGroup(99)).rejects.toThrow('do not hold');
  });
  it('changing the LP source clears synced grants while manual and direct grants remain independent', async () => {
    await createRole(custom);
    await sql`INSERT INTO explorer_group (group_id, name, luckperms_node) VALUES (99, 'Auditors', 'group.old')`.execute(db);
    await setGroupCapabilities(99, ['auditor']);
    await sql`INSERT INTO explorer_group_member (group_id, player_uuid_bin, source, synced_at) VALUES (99, ${uuidToBin(DAVE)}, 'luckperms', NOW())`.execute(db);
    await addManualMember(99, DAVE, ALICE);
    await setLuckpermsNode(99, 'group.new');
    const synced = await sql`SELECT 1 FROM explorer_group_member WHERE group_id = 99 AND source = 'luckperms'`.execute(db);
    expect(synced.rows).toHaveLength(0);
    expect((await resolveAuthorization(DAVE)).roles).toContain('auditor');
    await setDirectRoleMember('auditor', DAVE, true);
    await removeManualMember(99, DAVE);
    expect((await resolveAuthorization(DAVE)).roles).toContain('auditor');
    await setDirectRoleMember('auditor', DAVE, false);
    expect((await resolveAuthorization(DAVE)).roles).not.toContain('auditor');
  });
  it('deleting a group cascades its memberships and roles without deleting the role definition', async () => {
    await createRole(custom);
    await sql`INSERT INTO explorer_group (group_id, name) VALUES (99, 'Auditors')`.execute(db);
    await setGroupCapabilities(99, ['auditor']);
    await addManualMember(99, DAVE, ALICE);
    await deleteGroup(99);
    expect((await resolveAuthorization(DAVE)).roles).not.toContain('auditor');
    expect((await listRoleDefinitions()).some(r => r.key === 'auditor')).toBe(true);
    expect((await sql`SELECT 1 FROM explorer_group_member WHERE group_id = 99`.execute(db)).rows).toHaveLength(0);
    expect((await sql`SELECT 1 FROM explorer_group_capability WHERE group_id = 99`.execute(db)).rows).toHaveLength(0);
  });
  it('rechecks revoked memberships even if the request started with an old viewer', async () => {
    await sql`DELETE FROM explorer_role WHERE player_uuid_bin = ${uuidToBin(ALICE)} AND role = 'admin'`.execute(db);
    await expect(createRole(custom)).rejects.toThrow('Forbidden');
    expect((await listRoleDefinitions()).some(r => r.key === 'auditor')).toBe(false);
  });
  it('rejects invented permissions and assignments to roles outside the registry', async () => {
    // @ts-expect-error Exercise untrusted server-action input.
    await expect(createRole({ ...custom, permissions: ['invented'] })).rejects.toThrow('Unknown permission');
    await expect(setDirectRoleMember('missing', BOB, true)).rejects.toThrow('Unknown role');
  });
});

d('published config (economy_config)', () => {
  it('reads the tax keys Treasury publishes, as UTC instants, and nothing else', async () => {
    const rows = await findConfigByPrefix('tax.');
    expect(rows.map((r) => r.config_key).sort()).toEqual([
      'tax.balance.brackets', 'tax.balance.enabled',
      'tax.source-income-tax.default-rate', 'tax.source-income-tax.enabled', 'tax.source-income-tax.plugin-rates',
    ]);
    const rates = parseTaxRates(rows);
    expect(rates.balanceTax?.brackets).toEqual([
      { band: '$0.00 up to $100,000.00', rate: '0%' },
      { band: '$100,000.00 and above', rate: '1%' },
    ]);
    expect(rates.balanceTax?.updatedAt).toBe('2026-10-02T10:00:00.000Z');
    expect(rates.incomeTax?.sources).toEqual([{ plugin: 'Jobs', rate: '10%' }]);
  });
});

d('explorer summary regressions', () => {
  it('pages item keys before aggregating, and counts items beyond featured cards', async () => {
    const { listItems, countItems, countDistinctItems } = await import('@/lib/sql/market');
    const before = await countDistinctItems(7);
    try {
      for (let i = 0; i < 14; i++) {
        await sql`INSERT INTO chestshop_sale (customer_uuid_bin, txn_id, direction, quantity, unit_price, total_price, tax_amount, item_key, material, item_name, occurred_at)
          VALUES (${uuidToBin(ALICE)}, 1, 'BUY', 2, 3, 6, 0, ${`audit-item-${i.toString().padStart(2, '0')}`}, 'STONE', 'Audit stone', NOW())`.execute(db);
      }
      await sql`INSERT INTO chestshop_sale (customer_uuid_bin, txn_id, direction, quantity, unit_price, total_price, tax_amount, item_key, material, item_name, occurred_at)
        VALUES (${uuidToBin(ALICE)}, 1, 'BUY', 1, 1, 1, 0, 'audit-item-old', 'STONE', 'Audit old stone', NOW() - INTERVAL 40 DAY)`.execute(db);
      expect(await countDistinctItems(7)).toBe(before + 14);
      expect(await countItems('Audit stone')).toBe(14);
      expect(await listItems({ search: 'Audit stone', limit: 20, offset: 0 })).toHaveLength(14);
      const page = await listItems({ search: 'audit-item-', limit: 3, offset: 2 });
      expect(page.map(i => i.item_key)).toEqual(['audit-item-02', 'audit-item-03', 'audit-item-04']);
      expect(page[0]).toMatchObject({ total_quantity: 2, total_volume: '6.00', trade_count: 1 });
    } finally { await sql`DELETE FROM chestshop_sale WHERE item_key LIKE 'audit-item-%'`.execute(db); }
  });

  it('fine totals include more than the recent-list cap and respect scope', async () => {
    const { getFineTotals, getRecentFines } = await import('@/lib/sql/government');
    const before = await getFineTotals(30, [5]);
    try {
      for (let i = 0; i < 105; i++) await issueFine({ reason: 'audit-total', amount: 2.5 });
      const total = await getFineTotals(30, [5]);
      expect(total.activeCount).toBe(before.activeCount + 105);
      expect(Number(total.activeAmount)).toBe(Number(before.activeAmount) + 262.5);
      expect(await getRecentFines(30, 100, [5])).toHaveLength(100);
      expect(await getFineTotals(30, [])).toEqual({ activeCount: 0, activeAmount: '0.00' });
    } finally { await deleteFines('audit-total'); }
  });
});

d('government fine browsing', () => {
  it('filters and paginates within the permitted account set', async () => {
    const { getRecentFines, countFines } = await import('@/lib/sql/government');
    try {
      for (let i = 0; i < 3; i++) await issueFine({ reason: 'audit-page', amount: 1, revoked: i === 2 });
      expect(await countFines(30, [5], { q: 'audit-page', status: 'active' })).toBe(2);
      expect(await countFines(30, [], { q: 'audit-page' })).toBe(0);
      const first = await getRecentFines(30, 1, [5], { q: 'audit-page', status: 'active' });
      const second = await getRecentFines(30, 1, [5], { q: 'audit-page', status: 'active', offset: 1 });
      expect(first).toHaveLength(1);
      expect(second).toHaveLength(1);
      expect(first[0]?.fine_id).not.toBe(second[0]?.fine_id);
      expect(second[0]?.revoked).toBe(0);
    } finally { await deleteFines('audit-page'); }
  });
});

d('shop offer comparisons', () => {
  it('orders by unit price and selected direction, with consistent stock filtering', async () => {
    const { listShops, countShops } = await import('@/lib/sql/market');
    const args = { itemKey: 'audit-offer', material: null, firmId: null, ownerUuid: null, buyable: false, inStock: false, search: null, limit: 10, offset: 0 };
    try {
      await sql`INSERT INTO chestshop_shop (world, sign_x, sign_y, sign_z, material, item_key, item_name, buy_price, sell_price, batch_qty, current_stock)
        VALUES ('audit', 1, 1, 1, 'STONE', 'audit-offer', 'Stone', 16, 8, 64, 64),
        ('audit', 2, 1, 1, 'STONE', 'audit-offer', 'Stone', 2, 1, 1, 5),
        ('audit', 3, 1, 1, 'STONE', 'audit-offer', 'Stone', NULL, 3, 1, 0)`.execute(db);
      await sql`UPDATE chestshop_shop SET estimated_capacity = CASE sign_x WHEN 1 THEN 128 WHEN 2 THEN 0 ELSE NULL END WHERE world = 'audit'`.execute(db);
      const buy = await listShops({ ...args, direction: 'buy' });
      expect(buy.map(s => s.current_space)).toEqual([128, 0]);
      expect(buy.map(s => s.sign_x)).toEqual([1, 2]);
      const sell = await listShops({ ...args, direction: 'sell' });
      expect(sell.map(s => s.sign_x)).toEqual([3, 2, 1]);
      expect(sell.map(s => s.current_space)).toEqual([null, 0, 128]);
      expect(await countShops({ ...args, direction: 'sell' })).toBe(3);
      await sql`UPDATE chestshop_shop SET current_stock = 63 WHERE world = 'audit' AND sign_x = 1`.execute(db);
      expect((await listShops({ ...args, direction: 'buy', inStock: true })).map(s => s.sign_x)).toEqual([2]);
      expect(await countShops({ ...args, direction: 'buy', inStock: true })).toBe(1);
    } finally { await sql`DELETE FROM chestshop_shop WHERE world = 'audit'`.execute(db); }
  });
});


d('reused firm names', () => {
  it('prefers the active firm and keeps archived history addressable', async () => {
    await sql`INSERT INTO firm (firm_id, display_name, proprietor_uuid_bin, is_archived, name_released)
      VALUES (901, 'ReusedName', ${uuidToBin(ALICE)}, 1, 1),
             (902, 'REUSEDNAME', ${uuidToBin(BOB)}, 1, 1),
             (903, 'ReusedName', ${uuidToBin(CAROL)}, 0, 0)`.execute(db);
    try {
      expect((await findFirmByDisplayName('reusedname'))?.firm_id).toBe(903);
      await sql`UPDATE firm SET is_archived = 1 WHERE firm_id = 903`.execute(db);
      expect((await findFirmByDisplayName('reusedname'))?.firm_id).toBe(903);
      const history = await sql<{ display_name: string }>`
        SELECT display_name FROM firm WHERE firm_id = 901`.execute(db);
      expect(history.rows[0]?.display_name).toBe('ReusedName');
    } finally {
      await sql`DELETE FROM firm WHERE firm_id IN (901, 902, 903)`.execute(db);
    }
  });
});

d('public taxation ledger aggregates', () => {
  it('counts government credits once, identifies writer tags, and dates fine refunds by settlement', async () => {
    const { getTaxRevenue } = await import('@/lib/sql/taxation');
    const { summarizeTaxRevenue } = await import('@/lib/taxation');
    // Dedicated IDs; no changes to the shared seed or its balance assertions.
    await sql`INSERT INTO accounts (account_id, account_type, display_name, is_archived, owner_uuid_bin)
      VALUES (9201, 'GOVERNMENT', 'Archived tax office', 1, UNHEX(REPEAT('00',16))), (9202, 'PERSONAL', 'Private payer', 0, UNHEX(REPEAT('00',16)))`.execute(db);
    const transfer = async (id: number, plugin: string, message: string, amount: string, age: number, reverse = false) => {
      await sql`INSERT INTO ledger_txns (txn_id, message, plugin_system, initiator_uuid_bin, settlement_time)
        VALUES (${id}, ${message}, ${plugin}, UNHEX(REPEAT('00', 16)), UTC_TIMESTAMP() - INTERVAL ${age} DAY)`.execute(db);
      await sql`INSERT INTO ledger_postings (txn_id, account_id, amount, memo) VALUES
        (${id}, 9201, ${reverse ? '-' + amount : amount}, ${message}),
        (${id}, 9202, ${reverse ? amount : '-' + amount}, ${message})`.execute(db);
    };
    try {
      await transfer(9201, 'treasury', 'Personal balance tax (1d @ 1%/wk)', '0.10', 1);
      await transfer(9202, 'business', 'Weekly Corporate Balance Tax', '0.20', 1);
      await transfer(9203, 'treasury', 'Income tax on deposit from Jobs ($10)', '0.30', 1);
      await transfer(9204, 'ChestShop', 'ChestShop sales tax (5% of 10) — private memo', '0.40', 1);
      await transfer(9205, 'treasury', 'Fine: private reason', '5.00', 10);
      await transfer(9206, 'treasury', 'Fine revoked: private reason', '5.00', 1, true);
      await transfer(9207, 'other', 'Personal balance tax (spoof)', '99.00', 1);
      await transfer(9208, 'treasury', 'Fine: no fine record', '99.00', 1);
      await transfer(9209, 'ChestShop', 'ordinary sale', '99.00', 1);
      await transfer(9210, 'treasury', 'Personal balance tax (old)', '99.00', 31);
      await transfer(9211, 'treasury', 'Personal balance tax (not a government credit)', '99.00', 1, true);
      // A second fine record sharing the one refund txn (revoke_txn_id isn't
      // unique; V29 only made the debit unique). Its own debit txn has no postings
      // to the office, so it contributes nothing — the refund must count once.
      await sql`INSERT INTO ledger_txns (txn_id, message, plugin_system, initiator_uuid_bin, settlement_time)
        VALUES (9212, 'Fine: duplicate historic record', 'treasury', UNHEX(REPEAT('00', 16)), UTC_TIMESTAMP() - INTERVAL 10 DAY)`.execute(db);
      await sql`INSERT INTO government_fines
        (fine_id, player_uuid_bin, gov_account_id, amount, reason, txn_id, issued_by_uuid_bin, revoked, revoke_txn_id)
        VALUES (9201, UNHEX(REPEAT('00',16)), 9201, 5, 'private reason', 9205, UNHEX(REPEAT('00',16)), 1, 9206),
               (9202, UNHEX(REPEAT('00',16)), 9201, 5, 'duplicate historic record', 9212, UNHEX(REPEAT('00',16)), 1, 9206)`.execute(db);
      const rows = (await getTaxRevenue(7)).filter(row => row.account_id === 9201);
      const summary = summarizeTaxRevenue(rows, 7, new Date());
      expect(summary.totals).toEqual({ collected: '1.00', refunded: '5.00', net: '-4.00' });
      expect(summary.byType.map(row => row.net)).toEqual(['0.10', '0.20', '0.30', '0.40', '-5.00']);
      expect(summary.accounts[0]?.name).toBe('Archived tax office');
      expect(summarizeTaxRevenue((await getTaxRevenue(30)).filter(row => row.account_id === 9201), 30, new Date()).totals)
        .toEqual({ collected: '6.00', refunded: '5.00', net: '1.00' });
      expect(Object.keys(rows[0] ?? {}).sort()).toEqual(['account_id', 'collected', 'date', 'display_name', 'kind', 'refunded']);
    } finally {
      await sql`DELETE FROM government_fines WHERE fine_id BETWEEN 9201 AND 9202`.execute(db);
      await sql`DELETE FROM ledger_postings WHERE txn_id BETWEEN 9201 AND 9212`.execute(db);
      await sql`DELETE FROM ledger_txns WHERE txn_id BETWEEN 9201 AND 9212`.execute(db);
      await sql`DELETE FROM accounts WHERE account_id IN (9201, 9202)`.execute(db);
    }
  });
});
