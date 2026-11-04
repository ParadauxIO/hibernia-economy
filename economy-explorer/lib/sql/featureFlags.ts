import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';
import type { Executor } from '@/lib/sql/role';
import type { FlagValue } from '@/lib/featureFlagSnapshot';

export interface FeatureFlagRow {
  name: string;
  description: string;
  enabled: number;
  revision: number;
  updated_at: Date;
  updated_by: string | null;
}
export async function readFlagValues(): Promise<FlagValue[]> {
  return (await sql<FlagValue>`SET STATEMENT max_statement_time = 3 FOR SELECT name, enabled FROM feature_flags`.execute(db)).rows;
}
export async function listFeatureFlags(executor: Executor = db): Promise<FeatureFlagRow[]> {
  return (await sql<FeatureFlagRow>`SELECT name, description, enabled, revision, updated_at, updated_by FROM feature_flags ORDER BY name`.execute(executor)).rows;
}
export async function lockFeatureFlag(name: string, executor: Executor): Promise<FeatureFlagRow | undefined> {
  return (await sql<FeatureFlagRow>`SELECT name, description, enabled, revision, updated_at, updated_by FROM feature_flags WHERE name = ${name} FOR UPDATE`.execute(executor)).rows[0];
}
export async function writeFeatureFlag(name: string, enabled: boolean, actor: string, executor: Executor): Promise<void> {
  await sql`UPDATE feature_flags SET enabled = ${enabled ? 1 : 0}, revision = revision + 1,
    updated_at = CURRENT_TIMESTAMP(3), updated_by = ${actor} WHERE name = ${name}`.execute(executor);
}
