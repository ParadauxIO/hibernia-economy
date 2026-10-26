import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';

export const PROVIDERS = ['Business', 'ChestShop', 'Treasury', 'TreasuryAPI'];
export function parseGuard(text) {
  const guard = JSON.parse(text);
  if (guard.version !== 1 || guard.schema !== 'system-plugin-key-v42' || !Array.isArray(guard.bundles) || !guard.bundles.length) throw new Error('Invalid schema compatibility guard');
  for (const bundle of guard.bundles) {
    if (!/^[a-f0-9]{40}$/.test(bundle.sha) || !Array.isArray(bundle.jars) || bundle.jars.length !== 4 || new Set(bundle.jars).size !== 4) throw new Error('Invalid approved bundle identity');
    if (Object.keys(bundle.providers ?? {}).sort().join() !== [...PROVIDERS].sort().join()) throw new Error('All four economy providers required');
    if (Object.values(bundle.providers).sort().join() !== [...bundle.jars].sort().join()) throw new Error('Provider/file mapping mismatch');
    for (const jar of bundle.jars) {
      if (path.basename(jar) !== jar || !jar.endsWith('.jar') || !/^[a-f0-9]{64}$/.test(bundle.hashes?.[jar] ?? '')) throw new Error('Invalid approved JAR/hash');
    }
  }
  return guard;
}
export function loadGuard(file, required) {
  if (!fs.existsSync(file)) {
    if (required) throw new Error('Required schema compatibility guard is missing');
    return null;
  }
  return parseGuard(fs.readFileSync(file, 'utf8'));
}
export function approvedBundle(guard, sha, jars) {
  const bundle = guard?.bundles.find((b) => b.sha === sha);
  if (!bundle || (jars && [...jars].sort().join() !== [...bundle.jars].sort().join())) throw new Error('Bundle is not approved for the current database schema');
  return bundle;
}
function providerName(file) {
  const result = spawnSync('unzip', ['-p', file, 'plugin.yml', 'paper-plugin.yml'], { encoding: 'utf8', maxBuffer: 1024 * 1024 });
  if (result.error) throw result.error;
  return result.stdout?.match(/^name:\s*['"]?([^'"\s]+)/m)?.[1] ?? null;
}
export function verifyBundleDirectory(bundle, directory, readProvider = providerName) {
  for (const jar of bundle.jars) {
    const file = path.join(directory, jar);
    if (!fs.existsSync(file) || !fs.lstatSync(file).isFile()) throw new Error('Approved JAR missing or not a regular file');
    const hash = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
    if (hash !== bundle.hashes[jar]) throw new Error('Installed/source JAR hash differs from approval');
  }
  const found = [];
  for (const file of fs.readdirSync(directory).filter((f) => f.endsWith('.jar'))) {
    const provider = readProvider(path.join(directory, file));
    if (PROVIDERS.includes(provider)) found.push([provider, file]);
  }
  if (found.length !== 4 || found.some(([provider, file]) => bundle.providers[provider] !== file)) throw new Error('Economy provider duplicate or wrong JAR');
  return bundle;
}
export function verifyInstalled(guard, directory, readProvider) {
  for (const bundle of guard.bundles) {
    try { return verifyBundleDirectory(bundle, directory, readProvider); } catch { /* Try another explicitly approved bundle. */ }
  }
  throw new Error('No complete approved economy bundle is installed; keep maintenance paused');
}
