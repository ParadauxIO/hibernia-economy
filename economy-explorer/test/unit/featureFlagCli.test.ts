import { expect, it } from 'vitest';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const exec = promisify(execFile);
it('rejects malformed operator commands before attempting a database connection', async () => {
  for (const args of [[], ['list', 'extra'], ['set', 'Business.preview', 'on', 'actor'],
    ['set', 'business.preview', 'true', 'actor'], ['set', 'business.preview', 'on'],
    ['set', 'bad', 'off', 'actor'], ['set', 'business.preview\n', 'off', 'actor']]) {
    await expect(exec(process.execPath, ['scripts/feature-flags.mjs', ...args], {
      env: { ...process.env, DB_HOST: '', DB_PASSWORD: 'must-not-be-printed' },
    })).rejects.toMatchObject({ code: 1, stderr: expect.stringContaining('Usage:') });
  }
  await expect(exec(process.execPath, ['scripts/feature-flags.mjs', 'list'], {
    env: { ...process.env, DB_HOST: '', DB_PASSWORD: 'must-not-be-printed' },
  })).rejects.toMatchObject({ code: 1, stderr: 'DB_HOST, DB_NAME and DB_USERNAME are required.\n' });
});
