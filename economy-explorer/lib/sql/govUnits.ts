import 'server-only';
import { sql } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';

// Government units (V45): a presentation grouping of GOVERNMENT accounts.
// Pure queries; who may read or change them is decided in
// lib/services/governmentScope.ts and lib/services/governmentUnits.ts.

export interface GovUnitRow {
  unit_id: number;
  slug: string;
  name: string;
  parent_unit_id: number | null;
  description: string | null;
  created_at: Date;
  created_by_uuid: string | null;
}

/** Every unit. The table holds tens of rows at most. */
export async function listGovUnits(): Promise<GovUnitRow[]> {
  const r = await sql<Omit<GovUnitRow, 'created_by_uuid'> & { created_by_uuid_bin: Buffer | null }>`
    SELECT unit_id, slug, name, parent_unit_id, description, created_at, created_by_uuid_bin
    FROM explorer_gov_unit
    ORDER BY name, unit_id
  `.execute(db);
  return r.rows.map(({ created_by_uuid_bin, ...row }) => ({
    ...row,
    unit_id: Number(row.unit_id),
    parent_unit_id: row.parent_unit_id === null ? null : Number(row.parent_unit_id),
    created_by_uuid: created_by_uuid_bin ? binToUuid(created_by_uuid_bin) : null,
  }));
}

/**
 * Unit membership of active GOVERNMENT accounts. Archived or retyped accounts
 * drop out here, so a unit's "N accounts" never counts something no viewer can
 * be shown.
 */
export async function listGovUnitAccounts(): Promise<{ unit_id: number; account_id: number }[]> {
  const r = await sql<{ unit_id: number; account_id: number }>`
    SELECT ua.unit_id, ua.account_id
    FROM explorer_gov_unit_account ua
    JOIN accounts a ON a.account_id = ua.account_id
    WHERE a.account_type = 'GOVERNMENT' AND a.is_archived = 0
  `.execute(db);
  return r.rows.map((row) => ({ unit_id: Number(row.unit_id), account_id: Number(row.account_id) }));
}

export interface GovUnitInput {
  slug: string;
  name: string;
  parentUnitId: number | null;
  description: string | null;
}

export async function insertGovUnit(input: GovUnitInput, createdBy: string | null): Promise<number> {
  const r = await sql`
    INSERT INTO explorer_gov_unit (slug, name, parent_unit_id, description, created_by_uuid_bin)
    VALUES (${input.slug}, ${input.name}, ${input.parentUnitId}, ${input.description}, ${createdBy ? uuidToBin(createdBy) : null})
  `.execute(db);
  return Number(r.insertId);
}

export async function updateGovUnit(unitId: number, input: GovUnitInput): Promise<number> {
  const r = await sql`
    UPDATE explorer_gov_unit
    SET slug = ${input.slug}, name = ${input.name}, parent_unit_id = ${input.parentUnitId}, description = ${input.description}
    WHERE unit_id = ${unitId}
  `.execute(db);
  return Number(r.numAffectedRows ?? 0n);
}

/** Children move to the top level (FK ON DELETE SET NULL); account links go with the unit. */
export async function deleteGovUnit(unitId: number): Promise<number> {
  const r = await sql`DELETE FROM explorer_gov_unit WHERE unit_id = ${unitId}`.execute(db);
  return Number(r.numAffectedRows ?? 0n);
}

/** Puts an account in a unit, moving it out of any other (one unit per account). */
export async function assignGovUnitAccount(unitId: number, accountId: number): Promise<void> {
  await db.transaction().execute(async (trx) => {
    await sql`DELETE FROM explorer_gov_unit_account WHERE account_id = ${accountId}`.execute(trx);
    await sql`INSERT INTO explorer_gov_unit_account (unit_id, account_id) VALUES (${unitId}, ${accountId})`.execute(trx);
  });
}

export async function unassignGovUnitAccount(accountId: number): Promise<number> {
  const r = await sql`DELETE FROM explorer_gov_unit_account WHERE account_id = ${accountId}`.execute(db);
  return Number(r.numAffectedRows ?? 0n);
}

export interface AssignableAccountRow {
  account_id: number;
  display_name: string | null;
  owner_uuid: string | null;
  owner_name: string | null;
  balance: string;
  unit_id: number | null;
}

/** Every active GOVERNMENT account and its unit, for /admin/gov-units. A few dozen rows. */
export async function listAssignableGovAccounts(): Promise<AssignableAccountRow[]> {
  const r = await sql<Omit<AssignableAccountRow, 'owner_uuid'> & { owner_uuid_bin: Buffer | null }>`
    SELECT a.account_id, a.display_name, a.owner_uuid_bin, fp.current_name AS owner_name,
           COALESCE(abm.balance, 0.00) AS balance, ua.unit_id
    FROM accounts a
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    LEFT JOIN explorer_gov_unit_account ua ON ua.account_id = a.account_id
    WHERE a.account_type = 'GOVERNMENT' AND a.is_archived = 0
    ORDER BY a.display_name, a.account_id
  `.execute(db);
  return r.rows.map(({ owner_uuid_bin, ...row }) => ({
    ...row,
    account_id: Number(row.account_id),
    unit_id: row.unit_id === null ? null : Number(row.unit_id),
    owner_uuid: owner_uuid_bin ? binToUuid(owner_uuid_bin) : null,
  }));
}

/** Whether an account is an active GOVERNMENT account (the only kind a unit may hold). */
export async function isActiveGovernmentAccount(accountId: number): Promise<boolean> {
  const r = await sql<{ ok: number }>`
    SELECT EXISTS(SELECT 1 FROM accounts WHERE account_id = ${accountId} AND account_type = 'GOVERNMENT' AND is_archived = 0) AS ok
  `.execute(db);
  return Number(r.rows[0]?.ok ?? 0) === 1;
}

/** Unit labels for already-authorized accounts; this never expands the scope. */
export async function govUnitsForAccounts(ids: readonly number[]): Promise<Map<number, { id: number; name: string }>> {
  if (!ids.length) return new Map();
  const result = await sql<{ account_id: number; unit_id: number; name: string }>`
    SELECT ua.account_id, u.unit_id, u.name
    FROM explorer_gov_unit_account ua
    JOIN explorer_gov_unit u ON u.unit_id = ua.unit_id
    WHERE ua.account_id IN (${sql.join(ids)})
  `.execute(db);
  return new Map(result.rows.map((r) => [Number(r.account_id), { id: Number(r.unit_id), name: r.name }]));
}
