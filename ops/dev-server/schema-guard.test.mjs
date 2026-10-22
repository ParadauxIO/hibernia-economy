import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import net from 'node:net';
import { fileURLToPath } from 'node:url';
import { parseGuard, loadGuard, verifyInstalled, approvedBundle, PROVIDERS } from './schema-guard.mjs';
const here = path.dirname(fileURLToPath(import.meta.url));
const fixtureJars = JSON.parse(fs.readFileSync(path.join(here, 'jar-fixtures.json')));
const sha = 'a'.repeat(40), old = 'b'.repeat(40);
function setup() {
  fs.mkdirSync(path.join(process.cwd(), '.context'), { recursive: true });
  const root = fs.mkdtempSync(path.join(process.cwd(), '.context', 'g-'));
  for (const sub of ['plugins', 'dev-deploy/history/' + sha, 'bin']) fs.mkdirSync(path.join(root, sub), { recursive: true });
  const bundle = { sha, jars: [], hashes: {}, providers: {} };
  for (const name of PROVIDERS) {
    const jar = name.toLowerCase() + '-fixture.jar', data = Buffer.from(fixtureJars[name], 'base64');
    fs.writeFileSync(path.join(root, 'dev-deploy/history', sha, jar), data);
    bundle.jars.push(jar); bundle.hashes[jar] = crypto.createHash('sha256').update(data).digest('hex'); bundle.providers[name] = jar;
  }
  const guard = { version: 1, schema: 'system-plugin-key-v42', bundles: [bundle] };
  fs.writeFileSync(path.join(root, 'approval.json'), JSON.stringify(guard));
  fs.writeFileSync(path.join(root, 'dev-deploy/state.json'), JSON.stringify({ paused: true, deployed: { sha: old, jars: ['old.jar'] }, failed: null }));
  fs.writeFileSync(path.join(root, 'bin/gh'), '#!/bin/sh\nexit 1\n', { mode: 0o755 });
  fs.writeFileSync(path.join(root, 'start-local.sh'), '#!/bin/bash\necho started >> starts.log\nif [ -f fail-boot ]; then echo "[00:00:00 INFO]: Error occurred while enabling Treasury vfixture"; fi\necho "[00:00:00 INFO]: Done (0.1s)!"\nwhile read -r line; do [ "$line" = stop ] && exit 0; done\n', { mode: 0o755 });
  return { root, bundle, guard };
}
const delay = (ms) => new Promise((r) => setTimeout(r, ms));
async function start(root) {
  const child = spawn(process.execPath, [path.join(here, 'agent.mjs')], { env: { ...process.env, SERVER_DIR: root, PATH: path.join(root, 'bin') + ':' + process.env.PATH, POLL_SECONDS: '600', START_TIMEOUT_MINUTES: '0.05' }, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.resume(); child.stderr.resume();
  for (let i = 0; i < 100; i++) { if (fs.existsSync(path.join(root, 'dev-deploy/control.sock'))) return child; await delay(10); }
  throw new Error('Fixture controller did not start');
}
async function ctl(root, text) {
  return new Promise((resolve, reject) => { const s = net.createConnection(path.join(root, 'dev-deploy/control.sock')); let out = ''; s.on('connect', () => s.write(text + '\n')); s.on('data', (b) => out += b); s.on('end', () => resolve(out.trim())); s.on('error', reject); });
}
async function stop(child) { if (child.exitCode !== null) return; child.kill('SIGTERM'); await new Promise((r) => child.once('exit', r)); }
function state(root) { return JSON.parse(fs.readFileSync(path.join(root, 'dev-deploy/state.json'))); }

test('missing/malformed marker fails closed and unapproved SHA is rejected', () => {
  const f = setup(); try {
    assert.throws(() => loadGuard(path.join(f.root, 'missing'), true), /missing/);
    assert.throws(() => parseGuard('{}'), /Invalid/);
    assert.throws(() => approvedBundle(f.guard, old), /not approved/);
  } finally { fs.rmSync(f.root, { recursive: true }); }
});
test('partial, tampered and duplicate-provider bundles cannot boot', () => {
  const f = setup(); try {
    const dir = path.join(f.root, 'plugins');
    for (const jar of f.bundle.jars) fs.copyFileSync(path.join(f.root, 'dev-deploy/history', sha, jar), path.join(dir, jar));
    assert.equal(verifyInstalled(f.guard, dir).sha, sha);
    const file = path.join(dir, f.bundle.jars[0]); fs.appendFileSync(file, 'tamper'); assert.throws(() => verifyInstalled(f.guard, dir));
    fs.copyFileSync(path.join(f.root, 'dev-deploy/history', sha, f.bundle.jars[0]), file);
    fs.copyFileSync(file, path.join(dir, 'duplicate-old-provider.jar')); assert.throws(() => verifyInstalled(f.guard, dir));
    fs.unlinkSync(path.join(dir, 'duplicate-old-provider.jar')); fs.unlinkSync(file); assert.throws(() => verifyInstalled(f.guard, dir));
  } finally { fs.rmSync(f.root, { recursive: true }); }
});
test('paused install survives restart; boot/adopt required; resume only approved bundle', async () => {
  const f = setup(); let child;
  try {
    child = await start(f.root);
    assert.match(await ctl(f.root, 'maintenance-arm ' + path.join(f.root, 'approval.json')), /armed/);
    assert.match(await ctl(f.root, 'resume'), /ERROR/); assert.equal(state(f.root).paused, true);
    assert.equal(fs.existsSync(path.join(f.root, 'starts.log')), false);
    assert.match(await ctl(f.root, 'maintenance-install ' + sha), /installed/);
    assert.match(await ctl(f.root, 'resume'), /Adopt/);
    await stop(child); child = await start(f.root);
    assert.equal(fs.existsSync(path.join(f.root, 'starts.log')), false);
    assert.match(await ctl(f.root, 'maintenance-boot ' + sha), /smoke passed/);
    assert.equal(JSON.parse(await ctl(f.root, 'status')).paused, true);
    assert.match(await ctl(f.root, 'maintenance-adopt ' + sha), /adopted/);
    assert.match(await ctl(f.root, 'resume'), /^resumed/);
    assert.equal(state(f.root).deployed.sha, sha); assert.equal(state(f.root).paused, false);
  } finally { if (child) await stop(child); fs.rmSync(f.root, { recursive: true }); }
});
test('failed boot remains paused, preserves candidate, never restores old JARs', async () => {
  const f = setup(); let child;
  try {
    fs.writeFileSync(path.join(f.root, 'fail-boot'), '1'); child = await start(f.root);
    await ctl(f.root, 'maintenance-arm ' + path.join(f.root, 'approval.json'));
    await ctl(f.root, 'maintenance-install ' + sha);
    assert.match(await ctl(f.root, 'maintenance-boot ' + sha), /Boot failed; no rollback/);
    const status = JSON.parse(await ctl(f.root, 'status'));
    assert.equal(status.serverRunning, false); assert.equal(status.paused, true);
    assert.equal(state(f.root).staged.bootVerified, false); assert.equal(verifyInstalled(f.guard, path.join(f.root, 'plugins')).sha, sha);
    await stop(child); child = await start(f.root);
    assert.equal(JSON.parse(await ctl(f.root, 'status')).serverRunning, false);
    assert.match(await ctl(f.root, 'resume'), /Adopt/);
  } finally { if (child) await stop(child); fs.rmSync(f.root, { recursive: true }); }
});
