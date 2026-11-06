import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { deploymentAllowed } from './deployment-policy.mjs';

const env = {
  GITHUB_REF: 'refs/heads/feature/example', GITHUB_SHA: 'a'.repeat(40),
  GITHUB_EVENT_NAME: 'push', GITHUB_ACTOR: 'ParadauxIO', GITHUB_TRIGGERING_ACTOR: 'ParadauxIO',
};
const event = {
  sender: { login: 'ParadauxIO' },
  head_commit: { id: env.GITHUB_SHA, author: { username: 'ParadauxIO' }, message: 'Try this change [DEPLOY]' },
};

test('the owner can deploy a tip commit from any non-main branch, including an empty marker commit', () => {
  for (const branch of ['feature/example', 'fix', 'develop']) {
    assert.equal(deploymentAllowed(event, { ...env, GITHUB_REF: `refs/heads/${branch}` }), true);
  }
  assert.equal(deploymentAllowed({ ...event, head_commit: { ...event.head_commit, message: '[DEPLOY]' } }, env), true);
});

test('ordinary commits, other users, reruns by others, tags and manual dev runs cannot deploy', () => {
  const rejected = [
    [event, { ...env, GITHUB_ACTOR: 'contributor' }],
    [event, { ...env, GITHUB_TRIGGERING_ACTOR: 'contributor' }],
    [event, { ...env, GITHUB_EVENT_NAME: 'workflow_dispatch' }],
    [event, { ...env, GITHUB_EVENT_NAME: 'pull_request' }],
    [event, { ...env, GITHUB_REF: 'refs/tags/release' }],
    [{ ...event, deleted: true }, env],
    [{ ...event, sender: { login: 'contributor' } }, env],
    [{ ...event, sender: undefined }, env],
    [{ ...event, head_commit: null }, env],
    [{ ...event, head_commit: { ...event.head_commit, author: { username: 'contributor' } } }, env],
    [{ ...event, head_commit: { ...event.head_commit, author: { name: 'ParadauxIO' } } }, env],
    [{ ...event, head_commit: { ...event.head_commit, author: { username: 'contributor' }, committer: { username: 'ParadauxIO' } } }, env],
    [{ ...event, head_commit: { ...event.head_commit, id: 'b'.repeat(40) } }, env],
    ...['ordinary change', '[deploy]', 'DEPLOY'].map(message => [
      { ...event, head_commit: { ...event.head_commit, message } }, env,
    ]),
    [{ ...event, commits: [event.head_commit], head_commit: { ...event.head_commit, message: 'new tip' } }, env],
  ];
  for (const [input, context] of rejected) assert.equal(deploymentAllowed(input, context), false, JSON.stringify([input, context]));
});

test('production remains main-only and is never published by a development-only workflow', () => {
  const main = { ...env, GITHUB_REF: 'refs/heads/main', GITHUB_ACTOR: 'contributor' };
  assert.equal(deploymentAllowed({}, main, true), true);
  assert.equal(deploymentAllowed({}, { ...main, GITHUB_EVENT_NAME: 'workflow_dispatch' }, true), true);
  assert.equal(deploymentAllowed(event, main), false);
  assert.equal(deploymentAllowed({}, { ...main, GITHUB_EVENT_NAME: 'pull_request' }, true), false);
  assert.equal(deploymentAllowed({}, env, true), false);
});

test('publication rechecks the triggering actor even when a previous policy job succeeded', () => {
  assert.equal(deploymentAllowed(event, env), true);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'deployment-policy-'));
  try {
    const eventPath = path.join(dir, 'event.json');
    const outputPath = path.join(dir, 'output');
    fs.writeFileSync(eventPath, JSON.stringify(event));
    const run = (overrides = {}, args = []) => {
      fs.writeFileSync(outputPath, '');
      const result = spawnSync(process.execPath, [fileURLToPath(new URL('./deployment-policy.mjs', import.meta.url)), ...args], {
        env: { ...process.env, ...env, ALLOW_PRODUCTION: 'false', GITHUB_EVENT_PATH: eventPath, GITHUB_OUTPUT: outputPath, ...overrides },
        encoding: 'utf8',
      });
      assert.ifError(result.error);
      return { status: result.status, output: fs.readFileSync(outputPath, 'utf8') };
    };

    // Selection jobs skip ordinary pushes successfully; publication must stop.
    assert.deepEqual(run({ GITHUB_TRIGGERING_ACTOR: 'contributor' }), { status: 0, output: 'allowed=false\n' });
    assert.deepEqual(run({ GITHUB_TRIGGERING_ACTOR: 'contributor' }, ['--require']), { status: 1, output: 'allowed=false\n' });
    assert.deepEqual(run({}, ['--require']), { status: 0, output: 'allowed=true\n' });
    fs.writeFileSync(eventPath, JSON.stringify({ ...event, head_commit: { ...event.head_commit, message: 'ordinary change' } }));
    assert.deepEqual(run({}, ['--require']), { status: 1, output: 'allowed=false\n' });
    fs.writeFileSync(eventPath, '{}');
    const main = { GITHUB_REF: 'refs/heads/main', GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_TRIGGERING_ACTOR: 'contributor' };
    assert.deepEqual(run(main, ['--require']), { status: 1, output: 'allowed=false\n' });
    assert.deepEqual(run({ ...main, ALLOW_PRODUCTION: 'true' }, ['--require']), { status: 0, output: 'allowed=true\n' });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
