#!/usr/bin/env node
// Mint a SERVICE API token for the Treasury REST API.
//
// A SERVICE key is the backend credential this app uses for its admin calls
// (TREASURY_ADMIN_TOKEN, lib/treasury.ts). Nothing in-game can issue one — the
// TreasuryAPI plugin refuses to let a player manage SERVICE keys — so it is
// minted here, with the same signing-key derivation and claims the plugin and
// REST API use (common/…/JwtKeys.java, JwtTokenVerifier.java).
//
// It never talks to the database. It prints the token on stdout and, on stderr,
// the SQL that makes the api_keys row agree with it (everything on stderr is
// valid SQL, so `2> apply.sql` gives a runnable file). A token is only accepted
// once its row carries the same jti, so nothing changes until that SQL is run.
//
// The signing secret is read from the JWT_SECRET environment variable (never an
// argument, so it stays out of shell history and `ps`). It must be the value the
// REST API runs with (`api.jwt-secret` / JWT_SECRET), byte for byte.
//
// Re-mint for an existing SERVICE key (after rotating the secret, or before expiry):
//
//   -- find it:  SELECT key_id, bin_to_uuid(owner_uuid_bin), revoked, expires_at
//   --             FROM api_keys WHERE key_type = 'SERVICE';
//   JWT_SECRET='base64:…' node scripts/mint-service-token.mjs \
//       --key-id 7 --owner <uuid> > token.txt 2> apply.sql
//
// Create the first SERVICE key:
//
//   node scripts/mint-service-token.mjs --create --owner <uuid>   # prints an INSERT; run it
//   JWT_SECRET='base64:…' node scripts/mint-service-token.mjs --key-id <returned id> --owner <uuid>
//
// `--owner` is the Minecraft UUID recorded as the ledger initiator of admin
// transfers made with the token; use the operator's own account.

import { createHash, createHmac, randomUUID } from 'node:crypto';
import { parseArgs } from 'node:util';

const MIN_KEY_BYTES = 32;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function fail(message) {
  console.error(`-- error: ${message}`);
  process.exit(1);
}

/** Mirror of JwtKeys.deriveHmacKey. */
function deriveKey(secret) {
  if (!secret || !secret.trim()) fail('JWT_SECRET is not set.');
  if (secret.startsWith('base64:')) {
    const encoded = secret.slice('base64:'.length).trim();
    // Buffer.from is lenient; the services' decoder is not. Reject what they would.
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(encoded) || encoded.length % 4 !== 0) {
      fail("JWT 'base64:' key material is not valid base64.");
    }
    const key = Buffer.from(encoded, 'base64');
    if (key.length < MIN_KEY_BYTES) {
      // Deliberately says nothing about the value itself, not even its length.
      fail(`JWT 'base64:' key material must be at least ${MIN_KEY_BYTES} bytes.`);
    }
    return key;
  }
  // A SQL comment, so stderr stays runnable as-is.
  console.error('-- warning: passphrase secret; using the legacy SHA-256 derivation, as the services do.');
  return createHash('sha256').update(secret, 'utf8').digest();
}

const b64url = (data) => Buffer.from(data).toString('base64url');

function sign(key, keyId, owner, jti, issuedAt, expiresAt) {
  const header = { kid: String(keyId), alg: 'HS256' };
  // SERVICE tokens carry no 'acc' / 'firm' claim.
  const claims = { sub: owner, type: 'SERVICE', jti, iat: issuedAt, exp: expiresAt };
  const signingInput = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
  return `${signingInput}.${createHmac('sha256', key).update(signingInput).digest('base64url')}`;
}

/** UTC `YYYY-MM-DD HH:MM:SS`, as the services store issued_at / expires_at. */
const sqlTime = (epochSeconds) => new Date(epochSeconds * 1000).toISOString().slice(0, 19).replace('T', ' ');

let args;
try {
  args = parseArgs({
    options: {
      owner: { type: 'string' },
      'key-id': { type: 'string' },
      create: { type: 'boolean', default: false },
      days: { type: 'string', default: '180' },
    },
  }).values;
} catch (e) {
  fail(e.message);
}

if (!args.owner || !UUID_RE.test(args.owner)) fail('pass --owner <Minecraft UUID>.');
const owner = args.owner.toLowerCase();
const ownerHex = owner.replaceAll('-', '');

if (args.create) {
  if (args['key-id'] !== undefined) fail('--create makes a new row; do not pass --key-id with it.');
  // A placeholder jti: no token exists for it, so the row is inert until a token
  // is minted for its key_id.
  console.log(
    'INSERT INTO api_keys (key_type, account_id, firm_id, owner_uuid_bin, jwt_id, issued_at, expires_at, revoked)\n' +
      `VALUES ('SERVICE', NULL, NULL, UNHEX('${ownerHex}'), '${randomUUID()}', UTC_TIMESTAMP(), UTC_TIMESTAMP(), 0);\n` +
      'SELECT LAST_INSERT_ID() AS key_id;',
  );
  console.error('-- Run that SQL, then mint the token with --key-id <the key_id it returned>.');
  process.exit(0);
}

const keyId = Number(args['key-id']);
if (!Number.isInteger(keyId) || keyId <= 0) fail('pass --key-id <existing SERVICE key> (or --create to make one).');
const days = Number(args.days);
if (!Number.isInteger(days) || days <= 0) fail('--days must be a positive whole number.');

const key = deriveKey(process.env.JWT_SECRET ?? '');
const jti = randomUUID();
const now = Math.floor(Date.now() / 1000);
const expires = now + days * 86400;

console.error(
  '-- Apply this, or the token is rejected (TOKEN_SUPERSEDED). It also invalidates\n' +
    '-- every token previously minted for this key. Expect 1 row affected; 0 means the\n' +
    '-- key id, owner or key type is wrong, or the key is revoked.\n' +
    'UPDATE api_keys\n' +
    `   SET jwt_id = '${jti}', issued_at = '${sqlTime(now)}', expires_at = '${sqlTime(expires)}'\n` +
    ` WHERE key_id = ${keyId} AND key_type = 'SERVICE' AND revoked = 0\n` +
    `   AND owner_uuid_bin = UNHEX('${ownerHex}');`,
);
console.log(sign(key, keyId, owner, jti, now, expires));
