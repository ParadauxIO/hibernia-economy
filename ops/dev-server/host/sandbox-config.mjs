#!/usr/bin/env node
// Rewrites this clone's configs so nothing in them points at production, then
// checks that nothing production-specific is left. start-local.sh runs it
// before every start and refuses to start if it fails, so a clone.sh refresh
// that brings the production configs back is scrubbed again automatically.
//
//   node sandbox-config.mjs           scrub, then check
//   node sandbox-config.mjs --check   only check
//
// What it changes:
//   - every database connection: host 127.0.0.1 (the sandbox MariaDB), user
//     `sandbox` with the password supplied by Infisical; database
//     names are kept, start-local.sh creates them
//   - Discord: the bot token and every webhook URL are removed
//   - services with production credentials or side effects are switched off
//     or their keys removed (RULES below): the store, website sync, the
//     resource pack bucket, Keycloak, cross-server Redis, GitHub, bStats
// A file's production version is saved under .sandbox/prod-config/ before it
// is first changed. The sandbox has no route to production anyway; this is
// the second layer, so the configs themselves cannot reach it either.

import fs from 'node:fs';
import path from 'node:path';
import { enforceDiscordStaging } from './sandbox-discord.mjs';

const ROOT = process.env.SERVER_DIR ?? path.dirname(new URL(import.meta.url).pathname);
const BACKUP_DIR = path.join(ROOT, '.sandbox', 'prod-config');
const CHECK_ONLY = process.argv.includes('--check');
enforceDiscordStaging(ROOT, CHECK_ONLY);
const DISCORD_STAGING_TOKEN = localSecret('discordsrv-staging-token');

const DB_USER = 'sandbox';
// Supplied by Infisical: the database is also reachable from the
// cluster's pod network (start-local.sh), so a guessable password won't do.
const DB_PASSWORD = localSecret('db-sandbox');
const DB_HOST = '127.0.0.1';

// Production markers: any of these left in a config fails the check.
const PROD_DB_HOST = /\b[\w-]+\.bloom\.host\b/g;
const PROD_DB_USER = /\bu\d{3,6}_[A-Za-z0-9]{8,12}\b/g;
const DISCORD_WEBHOOK = /https?:\/\/(?:\w+\.)?discord(?:app)?\.com\/api\/webhooks\/[^\s'"]+/g;
const PROD_REDIS = /\b208\.92\.233\.61\b/g;
const MARKERS = [PROD_DB_HOST, PROD_DB_USER, DISCORD_WEBHOOK, PROD_REDIS];

// A password setting: password, Password, PASSWORD, user-password,
// mysql-password, userPassword, pass. Not keys that merely end in "pass",
// nor Plan's Key_pass/Store_pass (its web server keystore).
const PASSWORD_LINE = /^(\s*["']?([\w.-]*?)["']?\s*[:=]\s*)(.*?)(\s*,?)$/;
const isPasswordKey = (key) => /^(pass|password)$/i.test(key) || /[-_.]password$/i.test(key) || /[a-z](Password|Pass)$/.test(key);

// Settings outside the generic database and webhook handling. Each `path` is
// the chain of keys (YAML sections or HOCON blocks) down to the setting.
const RULES = [
  // ADT-193: PostmanPat uses only the staging notification channel; role sync and unavailable Realty stay off.
  { file: 'plugins/PostmanPat/postmanpatConfig.conf', path: ['module-config', 'verification', 'enabled'], value: false },
  { file: 'plugins/PostmanPat/postmanpatConfig.conf', path: ['module-config', 'realty', 'enabled'], value: false },
  { file: 'plugins/PostmanPat/postmanpatConfig.conf', path: ['module-config', 'mail', 'notification-channel'], value: '1562396102324650085' },
  // Only the explicitly supplied staging bot token is allowed (ADT-193).
  { file: 'plugins/DiscordSRV/config.yml', path: ['BotToken'], value: DISCORD_STAGING_TOKEN, sensitive: true },
  // Polls the store for purchases and marks them delivered: a dev server
  // would take real players' purchases.
  { file: 'plugins/CraftingStore/config.yml', path: ['api-key'], value: '' },
  // PlayerNotifications sends Discord DMs as the same production bot.
  { file: 'plugins/PlayerNotifications/modules/discord-adapter/discord.yml', path: ['bot-token'], value: '' },
  // Website account sync.
  { file: 'plugins/MineSync/config.yml', path: ['api-key'], value: '' },
  // Elections publishes results to GitHub gists.
  { file: 'plugins/Elections/github/github-gist.yml', path: ['personalAccessToken'], value: '' },
  // The production Keycloak realm, and the secret that signs API tokens.
  { file: 'plugins/TreasuryAPI/config.yml', path: ['keycloak', 'enabled'], value: false },
  { file: 'plugins/TreasuryAPI/config.yml', path: ['keycloak', 'client-secret'], value: '' },
  { file: 'plugins/TreasuryAPI/config.yml', path: ['api', 'jwt-secret'], value: localSecret('treasury-api-jwt'), sensitive: true },
  // Cross-server chat through the production Redis.
  { file: 'plugins/CarbonChat/config.conf', path: ['messaging-settings', 'enabled'], value: false },
  { file: 'plugins/CarbonChat/config.conf', path: ['messaging-settings', 'url'], value: DB_HOST },
  { file: 'plugins/CarbonChat/config.conf', path: ['messaging-settings', 'password'], value: '' },
  // Nexo uploads the resource pack to the production R2 bucket players
  // download it from.
  { file: 'plugins/Nexo/settings.yml', path: ['Pack', 'server', 'type'], value: 'HERMES' },
  { file: 'plugins/Nexo/settings.yml', path: ['Pack', 'server', 's3', 'access_key'], value: '' },
  { file: 'plugins/Nexo/settings.yml', path: ['Pack', 'server', 's3', 'secret_key'], value: '' },
  // Shared secrets other production services use to talk to this server.
  { file: 'plugins/Votifier/config.yml', path: ['tokens', 'default'], value: localSecret('votifier-default'), sensitive: true },
  { file: 'plugins/Votifier/config.yml', path: ['tokens', 'MCSL'], value: localSecret('votifier-mcsl'), sensitive: true },
  { file: 'plugins/Realty/modules/query-service/config.yml', path: ['shared-secret'], value: localSecret('realty-query'), sensitive: true },
  // Third-party API keys on the production accounts, and the webhook switch.
  { file: 'plugins/AdvancedAntiVPN/config.yml', path: ['Discord Webhook', 'Enabled'], value: false },
  { file: 'plugins/AdvancedAntiVPN/config.yml', path: ['Key'], value: '', all: true },
  // Usage statistics under the production server's identity.
  { file: 'plugins/bStats/config.yml', path: ['enabled'], value: false },
  // The sandbox database has no TLS.
  { file: 'plugins/Deportation/config.yml', path: ['database', 'arguments'], value: '?sslMode=disable' },
  { file: 'plugins/PlayerPoints/config.yml', path: ['mysql-settings', 'use-ssl'], value: false },
];

// Infisical is authoritative. Never generate or fall back to local secret files.
function localSecret(name) {
  const keys = {
    'db-sandbox': 'SANDBOX_DB_PASSWORD',
    'treasury-api-jwt': 'TREASURY_API_JWT',
    'votifier-default': 'VOTIFIER_DEFAULT',
    'votifier-mcsl': 'VOTIFIER_MCSL',
    'realty-query': 'REALTY_QUERY_SECRET',
    'discordsrv-staging-token': 'DISCORDSRV_STAGING_TOKEN',
  };
  const value = process.env[keys[name]];
  if (!value) throw new Error(`Missing ${keys[name]}; run through infisical_secrets.py staging`);
  return value;
}

// ---------------------------------------------------------------- files

const SUFFIXES = new Set(['.yml', '.yaml', '.conf', '.properties', '.json', '.toml', '.cfg', '.txt']);
const SKIP_DIRS = new Set([
  'userdata', 'playerdata', 'PlayerData', 'users', 'data', 'web', 'web.old', 'logs', 'cache',
  'libraries', '.paper-remapped', 'Translations', 'locales', 'lang', 'languages',
]);

function* configFiles() {
  for (const file of fs.readdirSync(ROOT)) {
    if (SUFFIXES.has(path.extname(file)) && fs.statSync(path.join(ROOT, file)).isFile()) yield file;
  }
  for (const top of ['plugins', 'config']) yield* walk(top, 1);
}

function* walk(rel, depth) {
  for (const entry of fs.readdirSync(path.join(ROOT, rel), { withFileTypes: true })) {
    const child = path.join(rel, entry.name);
    if (entry.isDirectory()) {
      if (depth < 6 && !SKIP_DIRS.has(entry.name)) yield* walk(child, depth + 1);
    } else if (entry.isFile() && SUFFIXES.has(path.extname(entry.name))
        && fs.statSync(path.join(ROOT, child)).size < 1_000_000) {
      yield child;
    }
  }
}

// ---------------------------------------------------------------- editing

const isHocon = (file) => file.endsWith('.conf');

// Formats `value` for the line it replaces, keeping that line's quoting.
function formatValue(value, oldRaw, hocon) {
  if (typeof value === 'boolean') return String(value);
  const quote = /^["']/.test(oldRaw) ? oldRaw[0] : (value === '' || /[:#{}[\],&*!|>%@`]/.test(value) ? (hocon ? '"' : "'") : '');
  return `${quote}${value}${quote}`;
}

// Sets the setting at `keyPath` in YAML or HOCON text, line by line so
// comments and layout survive. Returns the old raw values it replaced.
function setPath(lines, keyPath, value, { hocon, all }) {
  const replaced = [];
  const keyLine = (key) => new RegExp(`^(\\s*)["']?${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}["']?(\\s*[:=]\\s*|\\s*\\{\\s*$|\\s*:\\s*$)(.*?)(\\s*)$`);
  const indent = (line) => line.match(/^\s*/)[0].length;
  let start = 0;
  let end = lines.length;
  let parentIndent = -1;
  for (const [i, key] of keyPath.entries()) {
    const last = i === keyPath.length - 1;
    const re = keyLine(key);
    let found = false;
    for (let n = start; n < end; n++) {
      const line = lines[n];
      if (n > start && line.trim() && !line.trim().startsWith('#') && indent(line) <= parentIndent) {
        end = n; // left the parent section
        break;
      }
      const m = line.match(re);
      if (!m || line.trim().startsWith('#')) continue;
      if (!last) {
        parentIndent = indent(line);
        start = n + 1;
        found = true;
        break;
      }
      const [, lead, sep, raw, trail] = m;
      const next = formatValue(value, raw, hocon);
      if (raw !== next) {
        const key0 = line.slice(lead.length, line.length - sep.length - raw.length - trail.length);
        lines[n] = `${lead}${key0}${sep}${next}${trail}`;
        replaced.push(raw.replace(/^["']|["']$/g, ''));
      }
      found = true;
      if (!all) break;
    }
    if (!found) return null;
  }
  return replaced;
}

function scrubDatabase(lines, replaced) {
  const text = lines.join('\n');
  // Production connections, and ones already pointed here, whose password
  // may predate the current one.
  const local = new RegExp(`^\\s*["']?[\\w.-]*user[\\w.-]*["']?\\s*[:=]\\s*["']?${DB_USER}["']?\\s*,?\\s*$|//${DB_USER}:`, 'im');
  if (!PROD_DB_HOST.test(text) && !PROD_DB_USER.test(text) && !local.test(text)) return false;
  PROD_DB_HOST.lastIndex = PROD_DB_USER.lastIndex = 0;
  for (const [n, line] of lines.entries()) {
    if (line.trim().startsWith('#')) continue;
    let next = line
      .replace(/:\/\/([^:@/\s'"]+):([^@/\s'"]+)@/g, (_, user, pass) => {
        replaced.push(...[user, pass].filter((v) => v !== DB_USER && v !== DB_PASSWORD));
        return `://${DB_USER}:${DB_PASSWORD}@`;
      })
      .replace(PROD_DB_HOST, DB_HOST)
      .replace(PROD_DB_USER, (user) => {
        replaced.push(user);
        return DB_USER;
      });
    const m = next.match(PASSWORD_LINE);
    if (m && isPasswordKey(m[2])) {
      const raw = m[3].replace(/\s+#.*$/, '');
      const bare = raw.replace(/^["']|["']$/g, '');
      if (bare && bare !== DB_PASSWORD) {
        replaced.push(bare);
        next = `${m[1]}${formatValue(DB_PASSWORD, raw, false)}${m[4]}`;
      }
    }
    lines[n] = next;
  }
  return true;
}

function scrubWebhooks(lines, replaced) {
  let changed = false;
  for (const [n, line] of lines.entries()) {
    if (line.trim().startsWith('#')) continue;
    const next = line
      // An unquoted URL as the whole value becomes an empty string.
      .replace(new RegExp(`([:=]\\s*)${DISCORD_WEBHOOK.source}\\s*$`), (_, sep) => `${sep}''`)
      .replace(DISCORD_WEBHOOK, '');
    if (next !== line) {
      replaced.push(...(line.match(DISCORD_WEBHOOK) ?? []));
      lines[n] = next;
      changed = true;
    }
  }
  return changed;
}

// ---------------------------------------------------------------- main

function readLines(file) {
  const text = fs.readFileSync(path.join(ROOT, file), 'utf8');
  return { text, lines: text.split('\n') };
}

function writeScrubbed(file, before, lines) {
  const after = lines.join('\n');
  if (after === before) return false;
  // Save the production version: the first time, or when production values
  // came back (clone.sh). Never a file that was only re-scrubbed here, which
  // would replace the production copy with a sandbox one.
  const backup = path.join(BACKUP_DIR, file);
  const fromProduction = MARKERS.some((re) => {
    re.lastIndex = 0;
    return re.test(before);
  });
  if (!fs.existsSync(backup) || fromProduction) {
    fs.mkdirSync(path.dirname(backup), { recursive: true });
    fs.writeFileSync(backup, before);
  }
  fs.writeFileSync(path.join(ROOT, file), after);
  return true;
}

const files = [...configFiles()];
const secrets = new Set();
const problems = [];

const rulesByFile = Map.groupBy(RULES, (r) => r.file);

// Scrubs one file's lines in place; returns the values it replaced.
function scrubLines(file, lines, { requirePaths = true } = {}) {
  const cr = lines.map((l) => l.endsWith('\r'));
  for (const [n, l] of lines.entries()) if (cr[n]) lines[n] = l.slice(0, -1);
  const replaced = [];
  scrubDatabase(lines, replaced);
  scrubWebhooks(lines, replaced);
  for (const rule of rulesByFile.get(file) ?? []) {
    const old = setPath(lines, rule.path, rule.value, { hocon: isHocon(file), all: rule.all });
    if (old === null) {
      if (requirePaths) problems.push(`${file}: setting ${rule.path.join('.')} not found`);
    }
    else replaced.push(...old);
  }
  for (const [n, l] of lines.entries()) if (cr[n]) lines[n] = `${l}\r`;
  return replaced;
}

const remember = (values) => {
  for (const value of values) if (value.length >= 12) secrets.add(value);
};

// The production values replaced in earlier runs, from the saved copies, so
// the check below still knows them once the live configs are clean.
for (const file of fs.existsSync(BACKUP_DIR) ? fs.readdirSync(BACKUP_DIR, { recursive: true }) : []) {
  const full = path.join(BACKUP_DIR, file);
  if (fs.statSync(full).isFile()) remember(scrubLines(file, fs.readFileSync(full, 'utf8').split('\n'), { requirePaths: false }));
}

if (!CHECK_ONLY) {
  const changed = [];
  for (const file of files) {
    const { text, lines } = readLines(file);
    if (!/bloom\.host|u\d{3,6}_|api\/webhooks|BotToken|sandbox/.test(text) && !rulesByFile.has(file)) continue;
    const replaced = scrubLines(file, lines);
    if (writeScrubbed(file, text, lines)) changed.push(file);
    remember(replaced);
  }
  console.log(changed.length ? `sandbox-config: scrubbed ${changed.join(', ')}` : 'sandbox-config: configs already clean');
}

// The check: no production markers anywhere, and none of the secrets just
// replaced copied into some other file this script doesn't know about.
for (const file of files) {
  const text = fs.readFileSync(path.join(ROOT, file), 'utf8');
  for (const [n, line] of text.split('\n').entries()) {
    if (line.trim().startsWith('#')) continue;
    const marker = MARKERS.find((re) => {
      re.lastIndex = 0;
      return re.test(line);
    });
    if (marker) problems.push(`${file}:${n + 1}: production ${marker === DISCORD_WEBHOOK ? 'Discord webhook' : 'database or Redis reference'}`);
    else if ([...secrets].some((s) => line.includes(s))) problems.push(`${file}:${n + 1}: contains a production secret`);
  }
}
for (const rule of RULES) {
  if (!fs.existsSync(path.join(ROOT, rule.file))) continue;
  const { lines } = readLines(rule.file);
  const probe = [...lines];
  const old = setPath(probe, rule.path, rule.value, { hocon: isHocon(rule.file), all: rule.all });
  if (old === null) problems.push(`${rule.file}: setting ${rule.path.join('.')} not found`);
  else if (old.length) problems.push(`${rule.file}: ${rule.path.join('.')} is not ${rule.sensitive ? "<redacted>" : JSON.stringify(rule.value)}`);
}

if (problems.length) {
  console.error('sandbox-config: production settings remain; not safe to start:');
  for (const p of new Set(problems)) console.error(`  ${p}`);
  process.exit(1);
}
console.log(`sandbox-config: checked ${files.length} config files, nothing points at production`);
