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
    git,
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

// Recreate the exact merged collision tree using the unchanged SQL blobs.
const collisionMoves = [
  ['V38__firm_employment_history.sql', 'V39__firm_employment_history.sql'],
  ['V38__global_name_namespace.sql', 'V40__global_name_namespace.sql'],
];
function collisionFixture(t, { differentBase = false } = {}) {
  const f = fixture(t, { currentBase: true });
  for (const [oldName, newName] of collisionMoves) {
    if (newName.startsWith('V40__')) {
      copyFileSync(join(scripts, 'fixtures/withdrawn-namespace', newName), join(f.migrations, oldName));
    } else {
      renameSync(join(f.migrations, newName), join(f.migrations, oldName));
    }
  }
  const later = readdirSync(source).filter(name => Number(/^V(\d+)__/.exec(name)?.[1]) > 40);
  for (const name of later) rmSync(join(f.migrations, name));
  if (differentBase) appendFileSync(join(f.migrations, v18), '\n-- different collision base\n');
  f.git('add', '.');
  f.git('-c', 'commit.gpgsign=false', 'commit', '-qm', 'Colliding V38 migrations');
  if (!differentBase) {
    assert.equal(f.git('rev-parse', `HEAD:${migrationPath}`), '338af2234f3233d335f950ca31641550354db2df');
  }
  for (const [oldName, newName] of collisionMoves) {
    if (newName.startsWith('V40__')) rmSync(join(f.migrations, oldName));
    else renameSync(join(f.migrations, oldName), join(f.migrations, newName));
  }
  for (const name of later) copyFileSync(join(source, name), join(f.migrations, name));
  return f;
}

test('only the exact V38 collision repair passes and excludes its pending base scripts', (t) => {
  const f = collisionFixture(t);
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  const excludes = f.run('--upgrade-excludes');
  assert.equal(excludes.status, 0, excludes.stderr);
  assert.equal(excludes.stdout, collisionMoves.map(([oldName]) => oldName).join('\n') + '\n');
  assert.equal(f.run('--upgrade-target').stdout, '');
});

for (const [oldName, newName] of collisionMoves.filter(([, name]) => !name.startsWith('V40__'))) {
  for (const change of ['edit', 'delete', 'rename']) {
    test(`collision repair rejects ${change} of ${newName} before emitting exclusions`, (t) => {
      const f = collisionFixture(t);
      const path = join(f.migrations, newName);
      if (change === 'edit') appendFileSync(path, '\n-- changed SQL\n');
      if (change === 'delete') rmSync(path);
      if (change === 'rename') renameSync(path, join(f.migrations, 'V41__other.sql'));
      const result = f.run('--upgrade-excludes');
      assert.equal(result.status, 1);
      assert.ok(result.stderr.includes(oldName), result.stderr);
      assert.equal(result.stdout, '');
    });
  }
}

test('a different collision base cannot use the rename exception', (t) => {
  const result = collisionFixture(t, { differentBase: true }).run('--upgrade-excludes');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /renamed to/);
  assert.equal(result.stdout, '');
});

test('normal bases exclude nothing and still reject edits to the renumbered migrations', (t) => {
  const f = fixture(t, { currentBase: true });
  const result = f.run('--upgrade-excludes');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, '');
  appendFileSync(join(f.migrations, collisionMoves[0][1]), '\n-- later edit\n');
  assert.equal(f.run('--upgrade-excludes').status, 1);
});

function withdrawalFixture(t, { differentBase = false } = {}) {
  const f = fixture(t, { currentBase: true });
  for (const name of readdirSync(join(scripts, 'fixtures/withdrawn-namespace')).filter(name => name.endsWith('.sql'))) {
    copyFileSync(join(scripts, 'fixtures/withdrawn-namespace', name), join(f.migrations, name));
  }
  if (differentBase) appendFileSync(join(f.migrations, v18), '\n-- another deployment\n');
  f.git('add', '.');
  f.git('-c', 'commit.gpgsign=false', 'commit', '-qm', 'Before namespace withdrawal');
  for (const name of readdirSync(join(scripts, 'fixtures/withdrawn-namespace')).filter(name => name.endsWith('.sql'))) rmSync(join(f.migrations, name));
  return f;
}

test('exact staging-only namespace withdrawal excludes both scripts from upgrade rehearsal', (t) => {
  const f = withdrawalFixture(t);
  const result = f.run('--upgrade-excludes');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, 'V40__global_name_namespace.sql\nV41__allow_sole_name_claim_owner_change.sql\n');
});

test('withdrawal cannot exempt another base or changes to V42', (t) => {
  const other = withdrawalFixture(t, { differentBase: true });
  assert.match(other.run().stderr, /deleted/);
  const exact = withdrawalFixture(t);
  appendFileSync(join(exact.migrations, 'V42__stable_system_plugin_key.sql'), '\n-- changed\n');
  assert.match(exact.run().stderr, /modified after it was merged/);
});

for (const version of [40, 41]) {
  test(`withdrawn version V${version} cannot be reused`, (t) => {
    const f = fixture(t, { currentBase: true });
    writeFileSync(join(f.migrations, `V${version}__replacement.sql`), 'SELECT 1;\n');
    assert.match(f.run().stderr, /is retired/);
  });
}
