#!/usr/bin/env node
// Deploy agent for the DemocracyCraft dev server (the sandboxed production
// clone started by start-local.sh).
//
// It owns the server process, restarting it if it crashes, and polls the
// rolling `dev-latest` release that .github/workflows/dev-server-publish.yml
// publishes from `develop`. When that release's commit changes it:
//   1. downloads the jars into dev-deploy/history/<sha>/
//   2. stops the server
//   3. runs the economy-flyway migrations at that commit against the sandbox
//      database (started on its own with DB_ONLY=1)
//   4. replaces our plugin jars in plugins/ and starts the server again
//   5. waits for "Done (" with none of our plugins failing to enable, and
//      otherwise puts the previous jars back (migrations are not undone)
// and reports each deploy as a GitHub deployment to the democracycraft-dev
// environment.
//
//   node agent.mjs                 run the agent (what the systemd unit does)
//   node agent.mjs ctl status      ask the running agent what it is doing
//   node agent.mjs ctl pause       stop the server and stop deploying, e.g.
//                                  before running clone.sh; survives restarts
//   node agent.mjs ctl resume      start the server and deploy again
//   node agent.mjs ctl deploy      deploy the current build now, even if it
//                                  is already deployed or failed before
//   node agent.mjs ctl console <command...>
//                                  type a command into the server console
//
// Settings come from the environment; see hibernia-dev-server.service.

import { spawn } from 'node:child_process';
import { once } from 'node:events';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';

const SERVER_DIR = process.env.SERVER_DIR ?? path.join(os.homedir(), 'democracycraft-clone');
const REPO = process.env.REPO ?? 'ParadauxIO/hibernia-economy';
const RELEASE_TAG = process.env.RELEASE_TAG ?? 'dev-latest';
const ENVIRONMENT = process.env.ENVIRONMENT ?? 'democracycraft-dev';
const POLL_SECONDS = Number(process.env.POLL_SECONDS ?? 60);
const DB_PORT = process.env.DB_PORT ?? '33306';
const FLYWAY_JAVA_HOME = process.env.FLYWAY_JAVA_HOME ?? path.join(os.homedir(), '.jdks', 'jdk-21.0.12.1+1');
// The first start after a ChestShop change re-encodes ~35k item blobs, so
// this is generous.
const START_TIMEOUT_MS = Number(process.env.START_TIMEOUT_MINUTES ?? 20) * 60_000;
const STOP_TIMEOUT_MS = 5 * 60_000;
const KEEP_HISTORY = 5;

const STATE_DIR = path.join(SERVER_DIR, 'dev-deploy');
const STATE_FILE = path.join(STATE_DIR, 'state.json');
const HISTORY_DIR = path.join(STATE_DIR, 'history');
const CHECKOUT_DIR = path.join(STATE_DIR, 'repo');
const CONTROL_SOCKET = path.join(STATE_DIR, 'control.sock');
const PLUGINS_DIR = path.join(SERVER_DIR, 'plugins');
const START_SCRIPT = path.join(SERVER_DIR, 'start-local.sh');

const log = (...args) => console.log(`[dev-deploy ${new Date().toISOString()}]`, ...args);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ---------------------------------------------------------------- state

// deployed: { sha, jars } last good deploy; failed: sha not to retry until
// `ctl deploy`; paused: stay down and don't deploy.
let state = { deployed: null, failed: null, paused: false };
const loadState = () => {
  if (fs.existsSync(STATE_FILE)) state = { ...state, ...JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')) };
};
const saveState = () => {
  fs.writeFileSync(`${STATE_FILE}.tmp`, JSON.stringify(state, null, 2) + '\n');
  fs.renameSync(`${STATE_FILE}.tmp`, STATE_FILE);
};

// ---------------------------------------------------------------- processes

// Runs a command to completion, collecting its output (or passing it through
// when `inherit` is set). Never rejects; check `code`.
function run(cmd, args, { cwd, env, input, inherit = false } = {}) {
  return new Promise((resolve) => {
    const child = spawn(cmd, args, {
      cwd,
      env: { ...process.env, ...env },
      stdio: [input === undefined ? 'ignore' : 'pipe', inherit ? 'inherit' : 'pipe', inherit ? 'inherit' : 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout?.on('data', (d) => (stdout += d));
    child.stderr?.on('data', (d) => (stderr += d));
    if (input !== undefined) child.stdin.end(input);
    child.on('error', (err) => resolve({ code: -1, stdout, stderr: String(err) }));
    child.on('close', (code) => resolve({ code, stdout, stderr }));
  });
}

async function mustRun(cmd, args, opts) {
  const result = await run(cmd, args, opts);
  if (result.code !== 0) throw new Error(`${cmd} ${args.join(' ')} exited ${result.code}: ${result.stderr.trim().slice(-500)}`);
  return result;
}

// start-local.sh in its own process group, so a hung server can be killed
// with everything it started. Every output line goes to our stdout (the
// journal) and to the current line listeners.
function startSandbox(env) {
  const child = spawn('bash', [START_SCRIPT], {
    cwd: SERVER_DIR,
    env: { ...process.env, DB_PORT, ...env },
    stdio: ['pipe', 'pipe', 'pipe'],
    detached: true,
  });
  child.lineListeners = new Set();
  child.exited = once(child, 'exit').then(([code, signal]) => ({ code, signal }));
  for (const stream of [child.stdout, child.stderr]) {
    readline.createInterface({ input: stream }).on('line', (line) => {
      console.log(line);
      for (const listener of child.lineListeners) listener(line);
    });
  }
  return child;
}

// Resolves with the first line matching one of `patterns`, or null when the
// process exits or the timeout passes first.
function waitForLine(child, patterns, timeoutMs) {
  return new Promise((resolve) => {
    const finish = (value) => {
      clearTimeout(timer);
      child.lineListeners.delete(listener);
      resolve(value);
    };
    const listener = (line) => {
      if (patterns.some((p) => p.test(line))) finish(line);
    };
    const timer = setTimeout(() => finish(null), timeoutMs);
    child.lineListeners.add(listener);
    child.exited.then(() => finish(null));
  });
}

async function stopSandbox(child, how) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  how(child);
  const timeout = Symbol('timeout');
  if ((await Promise.race([child.exited, sleep(STOP_TIMEOUT_MS).then(() => timeout)])) !== timeout) return;
  log('sandbox did not stop in time; terminating it');
  try { process.kill(-child.pid, 'SIGTERM'); } catch {}
  if ((await Promise.race([child.exited, sleep(30_000).then(() => timeout)])) !== timeout) return;
  try { process.kill(-child.pid, 'SIGKILL'); } catch {}
  await child.exited;
}

// ---------------------------------------------------------------- server

let server = null;
let crashes = [];

function startServer() {
  if (server) return server;
  log('starting server');
  const child = startSandbox({});
  child.expectedExit = false;
  server = child;
  child.exited.then(({ code, signal }) => {
    if (server === child) server = null;
    if (child.expectedExit) return;
    log(`server exited unexpectedly (code ${code}, signal ${signal})`);
    const now = Date.now();
    crashes = crashes.filter((t) => now - t < 30 * 60_000).concat(now);
    if (crashes.length > 3) {
      log('server crashed 4 times in 30 minutes; leaving it down until `ctl resume` or the next deploy');
      return;
    }
    setTimeout(() => {
      if (!server && !busy && !state.paused) startServer();
    }, 30_000);
  });
  return child;
}

async function stopServer() {
  const child = server;
  if (!child) return;
  log('stopping server');
  child.expectedExit = true;
  await stopSandbox(child, (c) => c.stdin.write('stop\n'));
  server = null;
}

// Starts the server and judges the start: healthy once "Done (" is logged,
// unless one of `pluginNames` failed to load or enable first.
async function startServerChecked(pluginNames, jarNames) {
  const child = startServer();
  const failures = [];
  const failurePatterns = [
    ...pluginNames.map((n) => new RegExp(`Error occurred while (enabling|loading) ${n} v`)),
    ...jarNames.map((j) => new RegExp(`ERROR.*${j.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)),
  ];
  const onLine = (line) => {
    if (failurePatterns.some((p) => p.test(line))) failures.push(line.trim());
  };
  child.lineListeners.add(onLine);
  const done = await waitForLine(child, [/\]: Done \(/], START_TIMEOUT_MS);
  child.lineListeners.delete(onLine);
  if (!done) return { ok: false, reason: server === child ? 'server did not finish starting in time' : 'server exited while starting' };
  if (failures.length) return { ok: false, reason: failures[0] };
  return { ok: true };
}

// ---------------------------------------------------------------- plugins

// The plugin.yml name of a jar, or null for jars without one.
async function pluginName(jar) {
  const { stdout } = await run('unzip', ['-p', jar, 'plugin.yml', 'paper-plugin.yml']);
  return stdout.match(/^name:\s*['"]?([^'"\s]+)/m)?.[1] ?? null;
}

// Jars in plugins/ that provide one of `names`, whatever their file name, so
// a version bump doesn't leave the old jar loaded next to the new one.
async function installedJarsFor(names) {
  const found = [];
  for (const file of fs.readdirSync(PLUGINS_DIR)) {
    if (!file.endsWith('.jar')) continue;
    const name = await pluginName(path.join(PLUGINS_DIR, file));
    if (name && names.includes(name)) found.push(file);
  }
  return found;
}

async function installJars(fromDir, jars, replacingNames) {
  for (const file of await installedJarsFor(replacingNames)) fs.rmSync(path.join(PLUGINS_DIR, file));
  for (const jar of jars) fs.copyFileSync(path.join(fromDir, jar), path.join(PLUGINS_DIR, jar));
}

function pruneHistory(keep) {
  const dirs = fs.readdirSync(HISTORY_DIR)
    .filter((d) => !keep.includes(d) && d !== 'pre-agent')
    .map((d) => ({ d, mtime: fs.statSync(path.join(HISTORY_DIR, d)).mtimeMs }))
    .sort((a, b) => b.mtime - a.mtime);
  for (const { d } of dirs.slice(KEEP_HISTORY)) fs.rmSync(path.join(HISTORY_DIR, d), { recursive: true });
}

// ---------------------------------------------------------------- github

async function fetchManifest() {
  const { code, stdout } = await run('gh', ['release', 'download', RELEASE_TAG, '-R', REPO, '-p', 'manifest.json', '-O', '-']);
  if (code !== 0) return null; // no release, or it is being replaced right now
  try { return JSON.parse(stdout); } catch { return null; }
}

async function createDeployment(sha) {
  const { code, stdout, stderr } = await run('gh', ['api', '-X', 'POST', `repos/${REPO}/deployments`, '--input', '-'], {
    input: JSON.stringify({
      ref: sha,
      environment: ENVIRONMENT,
      auto_merge: false,
      required_contexts: [],
      description: 'Plugin bundle deployed by the dev server agent',
    }),
  });
  if (code !== 0) {
    log(`could not create GitHub deployment: ${stderr.trim()}`);
    return null;
  }
  return JSON.parse(stdout).id;
}

async function reportDeployment(id, deployState, description) {
  if (!id) return;
  const { code, stderr } = await run('gh', ['api', '-X', 'POST', `repos/${REPO}/deployments/${id}/statuses`, '--input', '-'], {
    input: JSON.stringify({ state: deployState, description: description.slice(0, 140) }),
  });
  if (code !== 0) log(`could not report deployment status: ${stderr.trim()}`);
}

// ---------------------------------------------------------------- deploy

async function migrate(sha) {
  if (!fs.existsSync(path.join(CHECKOUT_DIR, '.git'))) {
    await mustRun('git', ['clone', '--filter=blob:none', '--no-checkout', `https://github.com/${REPO}.git`, CHECKOUT_DIR]);
  }
  await mustRun('git', ['-C', CHECKOUT_DIR, 'fetch', '--quiet', 'origin', sha]);
  await mustRun('git', ['-C', CHECKOUT_DIR, 'checkout', '--quiet', '--force', '--detach', sha]);

  log('starting sandbox database for migrations');
  const db = startSandbox({ DB_ONLY: '1' });
  try {
    if (!(await waitForLine(db, [/Sandbox database is up/], 5 * 60_000))) throw new Error('sandbox database did not start');
    const schema = 's80037_economy';
    const result = await run('sh', [
      './gradlew', '--no-daemon', '--quiet', ':economy-flyway:flywayMigrate',
      `-PflywayUrl=jdbc:mysql://127.0.0.1:${DB_PORT}/${schema}`,
      '-PflywayUser=sandbox', '-PflywayPassword=sandbox', `-PflywaySchema=${schema}`,
    ], {
      cwd: CHECKOUT_DIR,
      env: { JAVA_HOME: FLYWAY_JAVA_HOME, PATH: `${FLYWAY_JAVA_HOME}/bin:${process.env.PATH}` },
      inherit: true,
    });
    if (result.code !== 0) throw new Error(`flywayMigrate exited ${result.code}`);
  } finally {
    await stopSandbox(db, (c) => c.stdin.end());
  }
}

async function deploy(manifest) {
  const { sha, jars } = manifest;
  const short = sha.slice(0, 7);
  log(`deploying ${short}: ${jars.join(', ')}`);

  const dir = path.join(HISTORY_DIR, sha);
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  await mustRun('gh', ['release', 'download', RELEASE_TAG, '-R', REPO, '-D', dir, '-p', '*.jar', '-p', 'manifest.json']);
  // The release is recreated on every push; make sure these files all came
  // from the build we meant to deploy.
  const downloaded = JSON.parse(fs.readFileSync(path.join(dir, 'manifest.json'), 'utf8'));
  if (downloaded.sha !== sha || !jars.every((j) => fs.existsSync(path.join(dir, j)))) {
    log('release changed while downloading; trying again on the next poll');
    fs.rmSync(dir, { recursive: true });
    return;
  }

  const deploymentId = await createDeployment(sha);
  await reportDeployment(deploymentId, 'in_progress', `Deploying ${short}`);
  const names = (await Promise.all(jars.map((j) => pluginName(path.join(dir, j))))).filter(Boolean);

  // What to go back to: the last good deploy, or, the first time, whatever
  // jars were installed before the agent took over.
  let previous;
  if (state.deployed && fs.existsSync(path.join(HISTORY_DIR, state.deployed.sha))) {
    previous = { dir: path.join(HISTORY_DIR, state.deployed.sha), jars: state.deployed.jars };
  } else {
    const preDir = path.join(HISTORY_DIR, 'pre-agent');
    fs.mkdirSync(preDir, { recursive: true });
    const existing = await installedJarsFor(names);
    for (const file of existing) fs.copyFileSync(path.join(PLUGINS_DIR, file), path.join(preDir, file));
    previous = { dir: preDir, jars: existing };
  }

  await stopServer();
  try {
    await migrate(sha);
  } catch (err) {
    log(`migration failed: ${err.message}`);
    state.failed = sha;
    saveState();
    startServer();
    await reportDeployment(deploymentId, 'failure', `Migrations failed for ${short}; kept the previous plugins`);
    return;
  }

  await installJars(dir, jars, names);
  const result = await startServerChecked(names, jars);
  if (result.ok) {
    log(`deployed ${short}`);
    state.deployed = { sha, jars };
    state.failed = null;
    saveState();
    pruneHistory([sha]);
    await reportDeployment(deploymentId, 'success', `Running ${short}`);
    return;
  }

  log(`deploy of ${short} is unhealthy (${result.reason}); restoring the previous plugins`);
  state.failed = sha;
  saveState();
  await stopServer();
  await installJars(previous.dir, previous.jars, names);
  startServer();
  await reportDeployment(deploymentId, 'failure', `Rolled back: ${result.reason}`);
}

let busy = false;

async function check({ force = false } = {}) {
  if (busy || state.paused) return;
  busy = true;
  try {
    const manifest = await fetchManifest();
    if (!manifest) return;
    if (!force && (manifest.sha === state.deployed?.sha || manifest.sha === state.failed)) return;
    await deploy(manifest);
  } catch (err) {
    log(`deploy failed: ${err.stack ?? err}`);
    if (!server && !state.paused) startServer();
  } finally {
    busy = false;
  }
}

// ---------------------------------------------------------------- control

async function handleControl(line) {
  const [command, ...rest] = line.trim().split(' ');
  switch (command) {
    case 'status':
      return JSON.stringify({ ...state, serverRunning: Boolean(server), busy }, null, 2);
    case 'console':
      if (!server) return 'server is not running';
      server.stdin.write(rest.join(' ') + '\n');
      return 'sent';
    case 'pause':
      state.paused = true;
      saveState();
      while (busy) await sleep(1000);
      await stopServer();
      return 'paused; the server is stopped and deploys are off';
    case 'resume':
      state.paused = false;
      saveState();
      crashes = [];
      if (!busy) startServer();
      check();
      return 'resumed';
    case 'deploy':
      if (busy) return 'a deploy is already running';
      check({ force: true });
      return 'deploying the current dev-latest build';
    default:
      return `unknown command: ${command}`;
  }
}

function listenForControl() {
  fs.rmSync(CONTROL_SOCKET, { force: true });
  net.createServer((socket) => {
    readline.createInterface({ input: socket }).once('line', async (line) => {
      socket.end((await handleControl(line)) + '\n');
    });
  }).listen(CONTROL_SOCKET);
}

function sendControl(args) {
  const socket = net.createConnection(CONTROL_SOCKET, () => socket.write(args.join(' ') + '\n'));
  socket.pipe(process.stdout);
  socket.on('error', (err) => {
    console.error(`agent not reachable on ${CONTROL_SOCKET}: ${err.message}`);
    process.exitCode = 1;
  });
}

// ---------------------------------------------------------------- main

async function main() {
  fs.mkdirSync(HISTORY_DIR, { recursive: true });
  loadState();
  listenForControl();

  let stopping = false;
  for (const signal of ['SIGTERM', 'SIGINT']) {
    process.on(signal, async () => {
      if (stopping) return;
      stopping = true;
      log(`${signal}; stopping server`);
      await stopServer();
      fs.rmSync(CONTROL_SOCKET, { force: true });
      process.exit(0);
    });
  }

  if (state.paused) {
    log('paused; run `ctl resume` to start the server');
  } else {
    // Deploy a build that landed while the agent was down before starting,
    // rather than starting the server only to bounce it straight away.
    await check();
    if (!server) startServer();
  }

  for (;;) {
    await sleep(POLL_SECONDS * 1000);
    await check();
  }
}

if (process.argv[2] === 'ctl') sendControl(process.argv.slice(3));
else main();
