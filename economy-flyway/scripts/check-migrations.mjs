#!/usr/bin/env node
// =============================================================================
// check-migrations.mjs — guard the Flyway migrations against edits that would
// break an already-migrated database.
//
//   node economy-flyway/scripts/check-migrations.mjs <base-ref>
//
// <base-ref> is what the migrations are compared against: origin/develop for a
// pull request, the previous commit for a push. The working tree is the "head"
// side, so the same command works locally before committing.
//
// Fails when:
//   * a versioned migration (V<n>__*.sql) that exists on the base ref was
//     modified, renamed or deleted. Flyway checksums every applied script, so
//     any of these makes flywayValidate/flywayMigrate fail on every database
//     that already ran it — write a new V<n+1> migration instead;
//   * a file in the migration directory is not V<n>__<snake_case>.sql or
//     R__<snake_case>.sql (no leading zeros, no dotted versions);
//   * two migrations share a version;
//   * a new migration's version isn't the next one after the base's highest
//     (lower would be skipped as out-of-order on a migrated DB; a gap is almost
//     always a renumbering mistake or a collision with another branch).
// Repeatable migrations (R__*.sql) may change freely — Flyway re-runs them.
//
// History is irrelevant: only base vs head is compared, so migrations edited
// before this guard existed (V17, V18, V20) don't trip it.
// =============================================================================

import { execFileSync } from 'node:child_process';
import { readdirSync, existsSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const VERSIONED = /^V([1-9]\d*)__([a-z0-9]+(?:_[a-z0-9]+)*)\.sql$/;
const REPEATABLE = /^R__([a-z0-9]+(?:_[a-z0-9]+)*)\.sql$/;

const baseRef = process.argv[2];
if (!baseRef || process.argv.length > 3) {
  console.error('usage: check-migrations.mjs <base-ref>');
  process.exit(2);
}

const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim();
const scriptDir = dirname(fileURLToPath(import.meta.url));
const root = git('-C', scriptDir, 'rev-parse', '--show-toplevel');
const migrationDir = relative(root, join(scriptDir, '..', 'src', 'main', 'resources', 'db', 'migration'));

try {
  git('-C', root, 'rev-parse', '--verify', '--quiet', `${baseRef}^{commit}`);
} catch {
  console.error(`check-migrations: base ref '${baseRef}' is not a commit in this clone (fetch it first).`);
  process.exit(2);
}

const errors = [];
const fail = (file, msg) => errors.push({ file, msg });

// ── Base side: path -> blob id, straight from the base tree ─────────────────
const base = new Map();
const lsTree = git('-C', root, 'ls-tree', '-r', baseRef, '--', `${migrationDir}/`);
for (const line of lsTree.split('\n').filter(Boolean)) {
  const [meta, path] = line.split('\t');
  base.set(path.slice(migrationDir.length + 1), meta.split(' ')[2]);
}

// ── Head side: the working tree ──────────────────────────────────────────────
const absDir = join(root, migrationDir);
const head = existsSync(absDir) ? readdirSync(absDir).sort() : [];
const headBlob = (name) => git('-C', root, 'hash-object', '--', join(migrationDir, name));

// (a) applied history must be immutable.
const headBlobs = new Map(head.map((name) => [name, headBlob(name)]));
for (const [name, blob] of base) {
  if (!VERSIONED.test(name)) continue;
  const where = join(migrationDir, name);
  if (!headBlobs.has(name)) {
    const renamedTo = [...headBlobs].find(([, b]) => b === blob)?.[0];
    fail(where, renamedTo
      ? `renamed to ${renamedTo}. Flyway tracks applied migrations by version and description; keep the original name.`
      : 'deleted. It is already applied on databases migrated from the base ref; restore it.');
  } else if (headBlobs.get(name) !== blob) {
    fail(where, 'modified after it was merged. Its checksum is recorded on every database that ran it, so flywayValidate would fail there. Revert it and put the change in a new migration.');
  }
}

// (b) naming, duplicates, ordering.
const versionsOf = (names) => {
  const out = new Map();
  for (const name of names) {
    const m = VERSIONED.exec(name);
    if (m) out.set(name, Number(m[1]));
  }
  return out;
};
const headVersions = versionsOf(head);
for (const name of head) {
  if (!VERSIONED.test(name) && !REPEATABLE.test(name)) {
    fail(join(migrationDir, name), 'does not match V<version>__<snake_case>.sql or R__<snake_case>.sql (positive integer version, no leading zeros, lower-case words joined by single underscores).');
  }
}

const byVersion = new Map();
for (const [name, v] of headVersions) byVersion.set(v, [...(byVersion.get(v) ?? []), name]);
for (const [v, names] of byVersion) {
  if (names.length > 1) fail(join(migrationDir, names[1]), `duplicate version V${v}: ${names.join(', ')}.`);
}

const baseMax = Math.max(0, ...versionsOf(base.keys()).values());
const added = [...headVersions].filter(([name]) => !base.has(name)).sort((a, b) => a[1] - b[1]);
let expected = baseMax + 1;
for (const [name, v] of added) {
  if (v <= baseMax) {
    fail(join(migrationDir, name), `V${v} is new but ${baseRef} already reaches V${baseMax}; databases migrated from it would skip this as out-of-order. Renumber it to V${expected}.`);
  } else if (v !== expected && !(byVersion.get(v)?.length > 1)) {
    fail(join(migrationDir, name), `V${v} leaves a gap after V${expected - 1}; the next migration should be V${expected}.`);
    expected = v + 1;
  } else {
    expected = v + 1;
  }
}

// ── Report ───────────────────────────────────────────────────────────────────
const annotate = process.env.GITHUB_ACTIONS === 'true';
for (const { file, msg } of errors) {
  console.error(annotate ? `::error file=${file}::${file} ${msg}` : `${file}: ${msg}`);
}
if (errors.length) {
  console.error(`\ncheck-migrations: ${errors.length} problem(s) against ${baseRef}.`);
  process.exit(1);
}
const repeatables = head.filter((n) => REPEATABLE.test(n)).length;
console.log(
  `check-migrations: OK against ${baseRef} — ${headVersions.size} versioned (${added.length} new, base max V${baseMax})` +
  (repeatables ? `, ${repeatables} repeatable` : '') + '.',
);
