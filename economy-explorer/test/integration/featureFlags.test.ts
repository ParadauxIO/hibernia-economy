import { beforeAll, afterAll, beforeEach, describe, expect, it, vi } from 'vitest';
import mysql from 'mysql2/promise';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const exec = promisify(execFile);
import { sql } from 'kysely';

vi.hoisted(() => { process.env.DB_NAME = `${process.env.DB_NAME ?? 'economy_test'}_flags`; });
vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));
vi.mock('next/headers', () => ({ headers: async () => new Headers() }));
vi.mock('@/lib/redis', () => ({ redis: () => null, tenantKey: (key: string) => key }));

import { HAS_DB, resetDb, testDbOptions, ALICE } from './db';
import { db } from '@/lib/db';
import { getViewer, type Viewer } from '@/lib/auth/viewer';
import { setFeatureFlag, getFeatureFlagsForAdmin } from '@/lib/services/featureFlags';
import { readFlagValues } from '@/lib/sql/featureFlags';
import { FeatureFlagCache } from '@/lib/featureFlagSnapshot';
import { PERMISSIONS } from '@/lib/auth/policy';

const actor: Viewer = { anon: false, keycloakSub: 'flags-test', minecraftUuid: ALICE, minecraftName: 'Alice',
  linked: true, role: 'admin', capabilities: ['player', 'admin'], permissions: PERMISSIONS };
const suite = HAS_DB ? describe : describe.skip;
suite('release controls against migrated MariaDB', () => {
  beforeAll(async () => {
    if (!/test/i.test(process.env.DB_NAME ?? '')) throw new Error('Requires disposable test database');
    const options = testDbOptions();
    delete options.database;
    const connection = await mysql.createConnection(options);
    await connection.query(`CREATE DATABASE IF NOT EXISTS \`${process.env.DB_NAME}\``);
    await connection.end();
    await resetDb();
    await sql`INSERT IGNORE INTO explorer_role (player_uuid_bin, role) VALUES (UNHEX(REPLACE(${ALICE}, '-', '')), 'admin')`.execute(db);
  }, 60_000);
  beforeEach(async () => {
    vi.mocked(getViewer).mockResolvedValue(actor);
    await sql`DELETE FROM feature_flags`.execute(db);
    await sql`INSERT INTO feature_flags (name, description) VALUES ('business.preview', 'Preview')`.execute(db);
    await sql`INSERT IGNORE INTO explorer_role_permission (role_key, permission) VALUES ('admin', 'feature_flags.update')`.execute(db);
  });
  afterAll(async () => { await db.destroy(); });

  it('preserves the existing path before the flag-table migration is applied', async () => {
    await sql`RENAME TABLE feature_flags TO feature_flags_pending_migration`.execute(db);
    const existingPath = vi.fn(() => 'existing behavior');
    const newSchemaPath = vi.fn(() => { throw new Error('New schema must not be queried'); });
    try {
      const flags = new FeatureFlagCache(readFlagValues, () => 0, () => {});
      const result = await flags.isEnabled('business.preview') ? newSchemaPath() : existingPath();
      expect(result).toBe('existing behavior');
      expect(existingPath).toHaveBeenCalledOnce();
      expect(newSchemaPath).not.toHaveBeenCalled();
    } finally {
      await sql`RENAME TABLE feature_flags_pending_migration TO feature_flags`.execute(db);
    }
  });
  it('defaults off, publishes mutations to independent readers and audits exact before/after', async () => {
    let now = 0;
    const first = new FeatureFlagCache(readFlagValues, () => now);
    const second = new FeatureFlagCache(readFlagValues, () => now);
    expect(await first.isEnabled('business.preview')).toBe(false);
    await setFeatureFlag({ name: 'business.preview', enabled: true, revision: 1 });
    now = 5_000;
    expect(await first.isEnabled('business.preview')).toBe(true);
    expect(await second.isEnabled('business.preview')).toBe(true);
    const rows = await getFeatureFlagsForAdmin();
    expect(rows[0]).toMatchObject({ enabled: 1, revision: 2, updated_by: 'flags-test' });
    const audit = (await sql<{ before_json: string; after_json: string }>`SELECT before_json, after_json FROM explorer_audit WHERE action = 'feature_flag.update' ORDER BY audit_id DESC LIMIT 1`.execute(db)).rows[0]!;
    expect(JSON.parse(audit.before_json)).toMatchObject({ enabled: 0, revision: 1 });
    expect(JSON.parse(audit.after_json)).toMatchObject({ enabled: 1, revision: 2 });
    await sql`UPDATE feature_flags SET enabled = 0, revision = revision + 1`.execute(db);
    now = 10_000;
    expect(await first.isEnabled('business.preview')).toBe(false);
    expect(await second.isEnabled('business.preview')).toBe(false);
  });
  it('rejects stale concurrent edits, unknown keys and invalid input without changing the value', async () => {
    const result = await Promise.allSettled([
      setFeatureFlag({ name: 'business.preview', enabled: true, revision: 1 }),
      setFeatureFlag({ name: 'business.preview', enabled: false, revision: 1 }),
    ]);
    expect(result.filter(r => r.status === 'fulfilled')).toHaveLength(1);
    expect(result.filter(r => r.status === 'rejected')).toHaveLength(1);
    expect((await getFeatureFlagsForAdmin())[0]?.revision).toBe(2);
    await expect(setFeatureFlag({ name: 'business.missing', enabled: true, revision: 1 })).rejects.toThrow('Unknown');
    await expect(setFeatureFlag({ name: 'BAD.key', enabled: true, revision: 2 })).rejects.toThrow('Invalid flag key');
  });
  it('operator script explicitly sets known keys, repeats safely and rejects unknown keys', async () => {
    const invoke = (...args: string[]) => exec(process.execPath, ['scripts/feature-flags.mjs', ...args], { env: process.env });
    expect((await invoke('list')).stdout).toContain('business.preview');
    await invoke('set', 'business.preview', 'on', 'script-test');
    await invoke('set', 'business.preview', 'on', 'script-test-repeat');
    expect((await getFeatureFlagsForAdmin())[0]).toMatchObject({ enabled: 1, revision: 3, updated_by: 'script-test-repeat' });
    await invoke('set', 'business.preview', 'off', 'script-test');
    expect((await readFlagValues())[0]?.enabled).toBe(0);
    await expect(invoke('set', 'business.missing', 'on', 'script-test')).rejects.toMatchObject({ code: 1 });
    expect(await getFeatureFlagsForAdmin()).toHaveLength(1);
  });
  it('denies anonymous/read-only callers and rechecks revoked grants inside the transaction', async () => {
    vi.mocked(getViewer).mockResolvedValue({ anon: true, keycloakSub: null, minecraftUuid: null, minecraftName: null, linked: false, role: null, capabilities: [], permissions: [] });
    await expect(setFeatureFlag({ name: 'business.preview', enabled: true, revision: 1 })).rejects.toThrow('Sign-in');
    vi.mocked(getViewer).mockResolvedValue({ ...actor, permissions: ['feature_flags.read'] });
    await expect(setFeatureFlag({ name: 'business.preview', enabled: true, revision: 1 })).rejects.toThrow('Forbidden');
    vi.mocked(getViewer).mockResolvedValue(actor);
    await sql`DELETE FROM explorer_role_permission WHERE role_key = 'admin' AND permission = 'feature_flags.update'`.execute(db);
    await expect(setFeatureFlag({ name: 'business.preview', enabled: true, revision: 1 })).rejects.toThrow('Forbidden');
    expect((await readFlagValues())[0]?.enabled).toBe(0);
  });
});
