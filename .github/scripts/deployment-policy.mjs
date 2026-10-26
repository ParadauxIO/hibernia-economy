import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

export function deploymentAllowed(event, env, allowProduction = false) {
  if (!env.GITHUB_REF?.startsWith('refs/heads/') || event.deleted) return false;
  if (env.GITHUB_REF === 'refs/heads/main') {
    return allowProduction && ['push', 'workflow_dispatch'].includes(env.GITHUB_EVENT_NAME);
  }
  return env.GITHUB_EVENT_NAME === 'push'
    && env.GITHUB_ACTOR === 'ParadauxIO'
    && env.GITHUB_TRIGGERING_ACTOR === 'ParadauxIO'
    && event.sender?.login === 'ParadauxIO'
    && event.head_commit?.author?.username === 'ParadauxIO'
    && event.head_commit?.id === env.GITHUB_SHA
    && typeof event.head_commit?.message === 'string'
    && event.head_commit.message.includes('[DEPLOY]');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const allowed = deploymentAllowed(event, process.env, process.env.ALLOW_PRODUCTION === 'true');
  fs.appendFileSync(process.env.GITHUB_OUTPUT, `allowed=${allowed}\n`);
  console.log(allowed ? 'Deployment authorized.' : 'Deployment skipped: trigger does not match deployment policy.');
}
