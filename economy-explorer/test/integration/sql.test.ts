import { describe, it, expect, beforeAll } from 'vitest';
import { HAS_DB, resetDb, ALICE, BOB, CAROL, DAVE, BEDROCK, SECRETARY, PENNY } from './db';
import { accountLabel, looksLikeUuid } from '@/lib/format';
import { findAccount, findPostingsByTxnId, getTotalSupply, getPersonalSupply, canReadAccount } from '@/lib/sql/ledger';
import { getPersonalBalances, getBalanceDistribution } from '@/lib/sql/stats';
import { isFirmMember, hasFirmFinancialAccess, getAccountFirmId, getFirmStats, findFirmByDisplayName } from '@/lib/sql/firm';
import { listFinanceFirms } from '@/lib/sql/webhook';
import { findCapabilities, findPlayerUuidByName } from '@/lib/sql/group';
import { findAccountsForPlayer, getPlayerTotals } from '@/lib/sql/me';
import { findIdentityBySub } from '@/lib/sql/identity';
import { getMoneyFlow } from '@/lib/sql/moneyFlow';
import { listItemSales } from '@/lib/sql/market';
import { gini } from '@/lib/derived';
import { audit } from '@/lib/audit';
import { listAudit } from '@/lib/sql/audit';

// Real queries against a real MariaDB (no mocking). Skipped unless RUN_INTEGRATION=1.
const d = HAS_DB ? describe : describe.skip;

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
    expect(edges[0].amount).toBe('500.00');
  });
});

d('group capabilities', () => {
  it('returns the viewer capability for a manual group member', async () => {
    // Bob is a manual member of the "Viewers" group (grants viewer).
    expect(await findCapabilities(BOB)).toContain('viewer');
  });

  it('returns the viewer capability for a LuckPerms-synced member (the recon-cron path)', async () => {
    // Carol holds her membership via source='luckperms' — i.e. the reconciliation
    // cron synced her from the group's node. The same capability must resolve, so
    // a linked player gets the viewer role purely from their in-game group.
    expect(await findCapabilities(CAROL)).toContain('viewer');
  });

  it('returns nothing for a player in no groups', async () => {
    // The government-account secretary has account_access but no explorer_group.
    expect(await findCapabilities(SECRETARY)).toEqual([]);
  });
});

d('audit logging records privileged access', () => {
  it('persists an audit row that listAudit reads back', async () => {
    await audit({
      viewer: { anon: false, keycloakSub: 'e2e-admin', minecraftUuid: ALICE, minecraftName: 'Alice', linked: true, role: 'admin', capabilities: ['admin', 'viewer', 'government'] },
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
