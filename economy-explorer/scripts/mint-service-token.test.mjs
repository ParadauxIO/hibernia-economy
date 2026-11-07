import assert from 'node:assert/strict';
import { createHash, createHmac } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

function mint(secret) {
  return spawnSync(process.execPath, [
    fileURLToPath(new URL('./mint-service-token.mjs', import.meta.url)),
    '--key-id', '7', '--owner', '12345678-1234-1234-1234-123456789abc',
  ], { env: { JWT_SECRET: secret }, encoding: 'utf8' });
}

test('short legacy secrets produce neither a token nor an UPDATE', () => {
  for (const secret of ['x', '12345678901234567890123456789éé']) {
    const result = mint(secret);
    assert.equal(result.status, 1);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '-- error: JWT legacy passphrase must be at least 32 characters; use base64: random key material.\n');
  }
});

test('32-character legacy secrets preserve their exact UTF-8 signing key', () => {
  const secret = ' 12345678901234567890123456789é ';
  assert.equal(secret.length, 32);
  const result = mint(secret);
  assert.equal(result.status, 0, result.stderr);
  const [header, payload, signature] = result.stdout.trim().split('.');
  const key = createHash('sha256').update(secret, 'utf8').digest();
  assert.equal(signature, createHmac('sha256', key).update(`${header}.${payload}`).digest('base64url'));
  assert.match(result.stderr, /UPDATE api_keys/);
});

test('base64 keys still require at least 32 decoded bytes and use them directly', () => {
  assert.equal(mint(`base64:${Buffer.alloc(31, 7).toString('base64')}`).status, 1);
  const key = Buffer.alloc(32, 7);
  const result = mint(`base64:${key.toString('base64')}`);
  assert.equal(result.status, 0, result.stderr);
  const [header, payload, signature] = result.stdout.trim().split('.');
  assert.equal(signature, createHmac('sha256', key).update(`${header}.${payload}`).digest('base64url'));
});
