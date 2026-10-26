import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { releaseVersion, writeManifest } from './release-metadata.mjs';

const env = {
  GITHUB_REF: 'refs/heads/main',
  GITHUB_RUN_NUMBER: '142',
  GITHUB_RUN_ATTEMPT: '1',
  GITHUB_RUN_ID: '987654',
  GITHUB_SHA: 'abc123def456' + '0'.repeat(28),
};
const now = new Date('2026-10-21T12:00:00Z');

test('release identities distinguish commits, runs, retries and development builds', () => {
  assert.equal(releaseVersion(env, now), '2026.10.142.1-gabc123def456');
  const versions = [
    env,
    { ...env, GITHUB_RUN_NUMBER: '143' },
    { ...env, GITHUB_RUN_ATTEMPT: '2' },
    { ...env, GITHUB_SHA: 'def456abc123' + '0'.repeat(28) },
    { ...env, GITHUB_REF: 'refs/heads/feature/example' },
  ].map(input => releaseVersion(input, now));
  assert.equal(new Set(versions).size, versions.length);
  assert.equal(versions[4], '2026.10.142.1-dev-gabc123def456');
  assert.equal(releaseVersion(env, new Date('2027-01-01T00:00:00Z')), '2027.1.142.1-gabc123def456');
});

test('rejects unsupported refs and missing identity rather than publishing ambiguous builds', () => {
  for (const input of [
    { ...env, GITHUB_REF: 'refs/heads/' },
    { ...env, GITHUB_REF: 'refs/tags/main' },
    { ...env, GITHUB_SHA: '' },
    { ...env, GITHUB_RUN_NUMBER: undefined },
    { ...env, GITHUB_RUN_ATTEMPT: '0' },
  ]) assert.throws(() => releaseVersion(input, now));
});

test('manifest identifies the exact plugin bundle and rejects incomplete or stale bundles', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'release-metadata-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const input = { ...env, RELEASE_VERSION: '2026.10.142.1-gabc123def456' };
  const jars = ['business', 'chestshop', 'treasury', 'treasury-api-plugin']
    .map(name => `${name}-${input.RELEASE_VERSION}.jar`).sort();
  for (const name of jars) fs.writeFileSync(path.join(directory, name), 'abc');
  const result = writeManifest(directory, input, now);
  assert.equal(result.version, input.RELEASE_VERSION);
  assert.equal(result.sha, env.GITHUB_SHA);
  assert.equal(result.run, 987654);
  assert.equal(result.attempt, 1);
  assert.deepEqual(result.jars, jars); // Preserve the deploy agent's string-array contract.
  assert.equal(result.sha256[jars[0]], 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(directory, 'manifest.json'))), result);
  fs.writeFileSync(path.join(directory, jars[0]), 'changed');
  assert.notEqual(writeManifest(directory, input, now).sha256[jars[0]], result.sha256[jars[0]]);
  fs.unlinkSync(path.join(directory, jars[0]));
  assert.throws(() => writeManifest(directory, input, now), /exactly the four/);
  fs.writeFileSync(path.join(directory, 'business-old.jar'), 'abc');
  assert.throws(() => writeManifest(directory, input, now), /exactly the four/);
});
