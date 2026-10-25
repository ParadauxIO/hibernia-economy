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
// Historical corrections have narrowly pinned exceptions below.
// --upgrade-target prints only its applied version (or nothing for normal bases),
// after all checks pass, for upgrade-test.sh. --upgrade-excludes prints only
// pending colliding filenames to omit from its scratch base archive.
// =============================================================================

import { execFileSync } from 'node:child_process';
import { readdirSync, existsSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const VERSIONED = /^V([1-9]\d*)__([a-z0-9]+(?:_[a-z0-9]+)*)\.sql$/;
const REPEATABLE = /^R__([a-z0-9]+(?:_[a-z0-9]+)*)\.sql$/;

const baseRef = process.argv[2];
const upgradeTarget = process.argv[3] === '--upgrade-target';
const upgradeExcludes = process.argv[3] === '--upgrade-excludes';
if (!baseRef || process.argv.length > (upgradeTarget || upgradeExcludes ? 4 : 3)) {
  console.error('usage: check-migrations.mjs <base-ref> [--upgrade-target | --upgrade-excludes]');
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

// main at 36d7d90 contains V16–V18, but the release runbook's production
// baseline is V15. V17/V18 were corrected before deployment (PAR-35, PAR-249,
// PAR-250). Pin the entire old migration tree AND both exact blob transitions:
// this must not exempt another base, a later edit, rename, or deletion.
// Databases that applied the old V17/V18 still require manual reconciliation;
// this exception is not permission to repair their Flyway history automatically.
const preCutover = base.size > 0 &&
  git('-C', root, 'rev-parse', `${baseRef}:${migrationDir}`) === '9066f0c2cac4917ee0de67474243019ba3a60bdd';
const pendingCorrections = new Map([
  ['V17__economy_players.sql', '0aa41c0b0b708cf1cec7c6255c6ba5c36c7dc2ca:36c4cb858875bc5a917f2de78462df5ed31f25bb'],
  ['V18__consolidate_account_access.sql', 'dbab43188ee05aad197a5757570dbc7ebdfce4ea:ffa37fd6eae5ef75fd7649b575a4944769e9d63c'],
]);

// Three independently merged branches chose V38. Keep the first merged script
// (taxation) at V38 and permit only these byte-identical renames from the exact
// broken tree. Flyway cannot migrate that tree because versions are duplicated.
// The upgrade rehearsal excludes only the two pending collisions from its scratch
// base, then applies V39 normally; the namespace is now withdrawn.
// Never repair a deployed DB's history here.
const collisionBase = base.size > 0 &&
  git('-C', root, 'rev-parse', `${baseRef}:${migrationDir}`) === '338af2234f3233d335f950ca31641550354db2df';
const collisionRenames = new Map([
  ['V38__firm_employment_history.sql', ['V39__firm_employment_history.sql', '827c49a4013635d951a7fb5629435d8ae16b7817']],
  ['V38__global_name_namespace.sql', ['V40__global_name_namespace.sql', '10353f218560a91888356b24e207376301530185']],
]);

// V40/V41 were deployed only to disposable staging, which must be rebuilt
// from a pre-V40 backup. Permit withdrawal only from this exact migration tree.
// Keep both version numbers retired; V42 retains its original version/checksum.
const namespaceBase = base.size > 0 &&
  git('-C', root, 'rev-parse', `${baseRef}:${migrationDir}`) === '19edc6700e245645315bedc4a520c9dcbe74bfb8';
const withdrawn = new Map([
  ['V40__global_name_namespace.sql', '10353f218560a91888356b24e207376301530185'],
  ['V41__allow_sole_name_claim_owner_change.sql', '8f09be6c1063b512095e946a001ca609e38489b2'],
]);
const retiredVersions = new Set([40, 41]);

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
    if (namespaceBase && withdrawn.get(name) === blob) continue;
    if (collisionBase && name === 'V38__global_name_namespace.sql' &&
        blob === withdrawn.get('V40__global_name_namespace.sql')) continue;
    const correction = collisionBase && collisionRenames.get(name);
    if (correction && blob === correction[1] && headBlobs.get(correction[0]) === blob) continue;
    const renamedTo = [...headBlobs].find(([, b]) => b === blob)?.[0];
    fail(where, renamedTo
      ? `renamed to ${renamedTo}. Flyway tracks applied migrations by version and description; keep the original name.`
      : 'deleted. It is already applied on databases migrated from the base ref; restore it.');
  } else if (headBlobs.get(name) !== blob) {
    if (!preCutover || pendingCorrections.get(name) !== `${blob}:${headBlobs.get(name)}`) {
      fail(where, 'modified after it was merged. Its checksum is recorded on every database that ran it, so flywayValidate would fail there. Revert it and put the change in a new migration.');
    }
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
for (const [name, v] of headVersions) {
  byVersion.set(v, [...(byVersion.get(v) ?? []), name]);
  if (retiredVersions.has(v)) fail(join(migrationDir, name), `V${v} is retired after namespace withdrawal; do not reuse it.`);
}
for (const [v, names] of byVersion) {
  if (names.length > 1) fail(join(migrationDir, names[1]), `duplicate version V${v}: ${names.join(', ')}.`);
}

const baseMax = Math.max(0, ...versionsOf(base.keys()).values());
const added = [...headVersions].filter(([name]) => !base.has(name)).sort((a, b) => a[1] - b[1]);
let expected = baseMax + 1;
for (const [name, v] of added) {
  while (retiredVersions.has(expected)) expected++;
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
if (upgradeExcludes) {
  if (collisionBase) console.log([...collisionRenames.keys()].join('\n'));
  if (namespaceBase) console.log([...withdrawn.keys()].join('\n'));
  process.exit(0);
}
if (upgradeTarget) {
  if (preCutover) console.log('15');
  process.exit(0);
}
if (preCutover) {
  console.log('Pre-cutover release baseline: V15 applied; only the pinned V17/V18 corrections are permitted. See wiki/release-runbook.md.');
}
const repeatables = head.filter((n) => REPEATABLE.test(n)).length;
console.log(
  `check-migrations: OK against ${baseRef} — ${headVersions.size} versioned (${added.length} new, base max V${baseMax})` +
  (repeatables ? `, ${repeatables} repeatable` : '') + '.',
);
