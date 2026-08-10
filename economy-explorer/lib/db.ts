import 'server-only';
import { Kysely, MysqlDialect, type Generated } from 'kysely';
import { createPool } from 'mysql2';

const pool = createPool({
  host: process.env.DB_HOST,
  port: Number(process.env.DB_PORT ?? 3306),
  database: process.env.DB_NAME,
  user: process.env.DB_USERNAME,
  password: process.env.DB_PASSWORD,
  connectionLimit: Number(process.env.DB_POOL ?? 100),
  timezone: 'Z',
  dateStrings: false,
  supportBigNumbers: true,
  bigNumberStrings: false,
  decimalNumbers: false, // keep DECIMAL as string — money never goes through IEEE 754
});

// Runaway-query backstop. A single pathological SSR query (e.g. an unbounded
// self-join) used to hold a pooled connection for ~50s and starve every other
// request. A server-side statement timeout caps query runtime so a slow read
// fails fast instead of draining the pool. The stack is MariaDB, whose knob is
// max_statement_time (in SECONDS) — not MySQL's max_execution_time (ms). The
// no-op callback swallows any error so a failed SET can never poison the pooled
// connection. Set per physical connection as it joins the pool.
const STMT_TIMEOUT_S = Number(process.env.DB_STMT_TIMEOUT_S ?? 15);
pool.on('connection', (conn) => {
  conn.query(`SET SESSION max_statement_time = ${STMT_TIMEOUT_S}`, () => {});
});

// mysql2's pool is structurally compatible with kysely's MysqlPool at runtime;
// the callback signatures differ in nullable-error types only.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const db = new Kysely<DB>({ dialect: new MysqlDialect({ pool: pool as any }) });

// Row-shape registry. Add interfaces as queries get ported. The empty {} on each
// entry means "no typed columns yet"; queries that use raw sql`` template literals
// pass their own row interface and don't depend on this registry.
export interface DB {
  explorer_role: { player_uuid_bin: Buffer; role: string; granted_at: Date; granted_by_uuid_bin: Buffer | null };
  explorer_identity: {
    keycloak_sub: string;
    player_uuid_bin: Buffer;
    minecraft_name: string | null;
    // NOT NULL DEFAULT CURRENT_TIMESTAMP (V3): DB-generated, so optional on insert.
    linked_at: Generated<Date>;
    linked_by: string | null;
  };
  explorer_link_code: {
    code: string;
    keycloak_sub: string;
    minecraft_name: string | null;
    created_at: Date;
    expires_at: Date;
  };
  explorer_group: {
    group_id: number;
    name: string;
    description: string | null;
    luckperms_node: string | null;
    created_at: Date;
    created_by_uuid_bin: Buffer | null;
  };
  explorer_group_capability: { group_id: number; capability: string };
  explorer_group_member: {
    group_id: number;
    player_uuid_bin: Buffer;
    source: 'manual' | 'luckperms';
    added_at: Date;
    added_by_uuid_bin: Buffer | null;
  };
  // Transaction-feed webhooks (PAR-151). The explorer writes owner-scoped
  // (api_key_id NULL) subscriptions here; the dispatcher in treasury-rest-api
  // reads the same rows. The DAL uses raw sql`` so these shapes are reference.
  webhook_subscription: {
    subscription_id: number;
    api_key_id: number | null;
    owner_uuid_bin: Buffer;
    key_type: 'PERSONAL' | 'BUSINESS' | 'GOVERNMENT';
    account_id: number | null;
    firm_id: number | null;
    target_url: string;
    secret: string;
    active: number;
    consecutive_failures: number;
    disabled_at: Date | null;
    created_at: Date;
    updated_at: Date;
  };
  webhook_delivery: {
    delivery_id: number;
    subscription_id: number;
    txn_id: number;
    account_id: number;
    status: 'PENDING' | 'DELIVERED' | 'FAILED';
    attempts: number;
    http_status: number | null;
    last_error: string | null;
    next_attempt_at: Date;
    created_at: Date;
    updated_at: Date;
  };
}

/** UUID string "11111111-2222-3333-4444-555555555555" → BINARY(16) Buffer. */
export function uuidToBin(uuid: string): Buffer {
  return Buffer.from(uuid.replace(/-/g, ''), 'hex');
}

/** BINARY(16) Buffer → canonical UUID string. */
export function binToUuid(bin: Buffer): string {
  const h = bin.toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
