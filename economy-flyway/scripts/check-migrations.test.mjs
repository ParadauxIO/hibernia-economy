import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync, appendFileSync, renameSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const scripts = dirname(fileURLToPath(import.meta.url));
const migrationPath = 'economy-flyway/src/main/resources/db/migration';
const source = resolve(scripts, '../src/main/resources/db/migration');
const v17 = 'V17__economy_players.sql';
const v18 = 'V18__consolidate_account_access.sql';

function fixture(t, { currentBase = false, differentBase = false } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'migration-guard-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const migrations = join(root, migrationPath);
  const script = join(root, 'economy-flyway/scripts/check-migrations.mjs');
  mkdirSync(migrations, { recursive: true });
  mkdirSync(dirname(script), { recursive: true });
  copyFileSync(join(scripts, 'check-migrations.mjs'), script);
  const git = (...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8' }).trim();
  git('init', '-q');
  git('config', 'user.name', 'Migration test');
  git('config', 'user.email', 'migration-test@example.invalid');
  git('config', 'core.autocrlf', 'false');
  for (const name of readdirSync(source)) {
    const version = Number(/^V(\d+)__/.exec(name)?.[1]);
    if (currentBase || version <= 18) copyFileSync(join(source, name), join(migrations, name));
  }
  if (!currentBase) {
    for (const name of [v17, v18]) {
      copyFileSync(join(scripts, 'fixtures/pre-cutover', name), join(migrations, name));
    }
  }
  if (differentBase) appendFileSync(join(migrations, v18), '\n-- another base\n');
  git('add', '.');
  git('-c', 'commit.gpgsign=false', 'commit', '-qm', 'Base migrations');
  if (!currentBase && !differentBase) {
    assert.equal(git('rev-parse', `HEAD:${migrationPath}`), '9066f0c2cac4917ee0de67474243019ba3a60bdd');
  }
  for (const name of readdirSync(source)) copyFileSync(join(source, name), join(migrations, name));
  return {
    migrations,
    run: (...args) => spawnSync(process.execPath, [script, 'HEAD', ...args], { encoding: 'utf8' }),
  };
}

test('the exact pre-cutover corrections pass and select V15 for the upgrade', (t) => {
  const f = fixture(t);
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Pre-cutover release baseline: V15/);
  const target = f.run('--upgrade-target');
  assert.equal(target.status, 0, target.stderr);
  assert.equal(target.stdout, '15\n');
});

test('a current base passes with no upgrade target limit', (t) => {
  const f = fixture(t, { currentBase: true });
  const result = f.run('--upgrade-target');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, '');
});

const v1 = readdirSync(source).find((name) => name.startsWith('V1__'));
for (const name of [v17, v18, v1]) {
  test(`further edits to ${name} fail, including in upgrade-target mode`, (t) => {
    const f = fixture(t);
    appendFileSync(join(f.migrations, name), '\n-- unapproved change\n');
    const result = f.run('--upgrade-target');
    assert.equal(result.status, 1);
    assert.match(result.stderr, /modified after it was merged/);
    assert.equal(result.stdout, '');
  });
}

test('the exception does not apply to any other base migration tree', (t) => {
  const result = fixture(t, { differentBase: true }).run();
  assert.equal(result.status, 1);
  assert.match(result.stderr, /V17__economy_players.sql.*modified/);
  assert.match(result.stderr, /V18__consolidate_account_access.sql.*modified/);
});

test('once the corrections are in the base, later edits still fail', (t) => {
  const f = fixture(t, { currentBase: true });
  appendFileSync(join(f.migrations, v17), '\n-- later change\n');
  assert.equal(f.run().status, 1);
});

test('deleting a pending migration still fails', (t) => {
  const f = fixture(t);
  rmSync(join(f.migrations, v17));
  const result = f.run();
  assert.equal(result.status, 1);
  assert.match(result.stderr, /deleted/);
});

test('renaming a pending migration still fails', (t) => {
  const f = fixture(t);
  renameSync(join(f.migrations, v18), join(f.migrations, 'V18__renamed.sql'));
  assert.equal(f.run().status, 1);
});

test('new migrations must still be consecutive', (t) => {
  const f = fixture(t);
  writeFileSync(join(f.migrations, 'V999__gap.sql'), 'SELECT 1;\n');
  const result = f.run();
  assert.equal(result.status, 1);
  assert.match(result.stderr, /leaves a gap/);
});

test('duplicate versions still fail', (t) => {
  const f = fixture(t);
  writeFileSync(join(f.migrations, 'V18__duplicate.sql'), 'SELECT 1;\n');
  const result = f.run();
  assert.equal(result.status, 1);
  assert.match(result.stderr, /duplicate version V18/);
});

function collisionFixture(t, { differentBase = false } = {}) {
  const f = fixture(t, { currentBase: true });
  const renames = [
    ['V38__firm_employment_history.sql', 'V39__firm_employment_history.sql'],
    ['V38__global_name_namespace.sql', 'V40__global_name_namespace.sql'],
  ];
  for (const [oldName, newName] of renames) renameSync(join(f.migrations, newName), join(f.migrations, oldName));
  if (differentBase) appendFileSync(join(f.migrations, 'V38__taxation_read_index.sql'), '\n-- different base\n');
  execFileSync('git', ['-C', f.migrations, 'add', '.']);
  execFileSync('git', ['-C', f.migrations, '-c', 'commit.gpgsign=false', 'commit', '-qm', 'Combined V38 collision']);
  for (const [oldName, newName] of renames) renameSync(join(f.migrations, oldName), join(f.migrations, newName));
  return f;
}

test('exact undeployed V38 collision permits only unchanged renames and upgrades from V37', t => {
  const f = collisionFixture(t);
  const result = f.run('--upgrade-target');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, '37\n');
});
for (const name of ['V39__firm_employment_history.sql', 'V40__global_name_namespace.sql', 'V38__taxation_read_index.sql']) {
  test(`V38 collision correction rejects SQL changes to ${name}`, t => {
    const f = collisionFixture(t);
    appendFileSync(join(f.migrations, name), '\n-- changed SQL\n');
    assert.equal(f.run().status, 1);
  });
}
test('V38 collision correction rejects any different base tree', t => {
  assert.equal(collisionFixture(t, { differentBase: true }).run().status, 1);
});
test('V38 collision correction rejects deleting a renamed migration', t => {
  const f = collisionFixture(t);
  rmSync(join(f.migrations, 'V39__firm_employment_history.sql'));
  assert.equal(f.run().status, 1);
});
