import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

// .env.example is the only place a new deployment learns which variables exist.
// Every process.env.X the app reads must appear there (uncommented if required,
// commented with its default if optional), so the file cannot drift.
const ROOT = process.cwd();
const SOURCES = ['app', 'components', 'lib', 'middleware.ts', 'next.config.ts'];
// Read by scripts and tooling, not by the running app.
const NOT_APP_CONFIG = new Set(['NODE_ENV', 'CI']);

function files(path: string): string[] {
  const full = join(ROOT, path);
  if (statSync(full).isFile()) return [full];
  return readdirSync(full).flatMap((entry) => files(join(path, entry)));
}

function variablesRead(): Set<string> {
  const found = new Set<string>();
  for (const file of SOURCES.flatMap(files).filter((f) => /\.(ts|tsx)$/.test(f))) {
    for (const match of readFileSync(file, 'utf8').matchAll(/process\.env\.([A-Z][A-Z0-9_]*)/g)) {
      found.add(match[1]);
    }
  }
  return found;
}

function variablesDocumented(): Set<string> {
  const documented = new Set<string>();
  for (const line of readFileSync(join(ROOT, '.env.example'), 'utf8').split('\n')) {
    const match = /^#?\s?([A-Z][A-Z0-9_]*)=/.exec(line);
    if (match) documented.add(match[1]);
  }
  return documented;
}

describe('.env.example', () => {
  it('documents every environment variable the app reads', () => {
    const documented = variablesDocumented();
    const missing = [...variablesRead()].filter((v) => !documented.has(v) && !NOT_APP_CONFIG.has(v)).sort();
    expect(missing, 'add these to economy-explorer/.env.example').toEqual([]);
  });
});
