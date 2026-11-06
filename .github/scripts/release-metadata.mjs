import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export function releaseVersion(env, now = new Date()) {
  if (!env.GITHUB_REF?.startsWith('refs/heads/') || env.GITHUB_REF === 'refs/heads/') {
    throw new Error('Releases must run from a branch');
  }
  for (const key of ['GITHUB_RUN_NUMBER', 'GITHUB_RUN_ATTEMPT']) {
    if (!/^[1-9][0-9]*$/.test(env[key] ?? '')) throw new Error(`Invalid ${key}`);
  }
  if (!/^[0-9a-f]{40}$/.test(env.GITHUB_SHA ?? '')) throw new Error('Invalid GITHUB_SHA');
  const month = `${now.getUTCFullYear()}.${now.getUTCMonth() + 1}`;
  const channel = env.GITHUB_REF !== 'refs/heads/main' ? '-dev' : '';
  return `${month}.${env.GITHUB_RUN_NUMBER}.${env.GITHUB_RUN_ATTEMPT}${channel}-g${env.GITHUB_SHA.slice(0, 12)}`;
}

export function writeManifest(directory, env, now = new Date()) {
  const version = env.RELEASE_VERSION;
  if (!version) throw new Error('Missing RELEASE_VERSION');
  const jars = ['business', 'chestshop', 'treasury', 'treasury-api-plugin']
    .map(name => `${name}-${version}.jar`).sort();
  const actual = fs.readdirSync(directory).filter(name => name.endsWith('.jar')).sort();
  if (JSON.stringify(actual) !== JSON.stringify(jars)) {
    throw new Error('Release must contain exactly the four plugins at RELEASE_VERSION');
  }
  const manifest = {
    version,
    sha: env.GITHUB_SHA,
    run: Number(env.GITHUB_RUN_ID),
    attempt: Number(env.GITHUB_RUN_ATTEMPT),
    builtAt: now.toISOString(),
    jars,
    sha256: Object.fromEntries(jars.map(name => [name,
      createHash('sha256').update(fs.readFileSync(path.join(directory, name))).digest('hex'),
    ])),
  };
  fs.writeFileSync(path.join(directory, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  return manifest;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv[2] === 'version') {
    const version = releaseVersion(process.env);
    fs.appendFileSync(process.env.GITHUB_OUTPUT, `version=${version}\n`);
    console.log(version);
  } else if (process.argv[2] === 'manifest') {
    writeManifest('release', process.env);
  } else {
    throw new Error('Usage: node release-metadata.mjs version|manifest');
  }
}
