#!/usr/bin/env node
// Re-measure the anonymous performance baseline recorded in perf-baseline.md (PAR-531).
//
//   node ops/perf-baseline.mjs https://economy.dev.paradaux.io [https://other.site …]
//
// For each route: SAMPLES GETs through curl, interleaved across routes and sites
// and spaced SPACING_MS apart, so a site sees one request every few seconds. The
// first sample of each route is reported on its own ("first", the closest an
// outside client gets to cold); the others are "warm". Then every script the HTML
// of `/` references is fetched once, as served (br/gzip) and decoded.
// Prints Markdown. Anonymous, read-only GETs.
import { execFileSync } from 'node:child_process';

const SAMPLES = Number(process.env.SAMPLES ?? 5);
const SPACING_MS = Number(process.env.SPACING_MS ?? 2500);
const ROUTES = (process.env.ROUTES ?? [
  '/', '/accounts', '/accounts/35156', '/accounts/146686', '/firms', '/firms/TSEGovtBank', '/market',
  '/economy/wealth', '/economy/taxation', '/government', '/money-flow', '/search?q=Planke', '/chestshop/sales', '/docs',
].join(',')).split(',');
const sites = process.argv.slice(2);
if (sites.length === 0) {
  console.error('usage: node ops/perf-baseline.mjs <base-url> [<base-url> …]');
  process.exit(2);
}

const CURL = ['-sS', '-A', 'economy-explorer-perf-baseline/1 (PAR-531)'];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Timing of one GET, accepting br/gzip like a browser. `wire` = bytes received. */
function timed(url) {
  const out = execFileSync('curl', [...CURL, '-H', 'Accept-Encoding: br, gzip', '-o', '/dev/null',
    '-w', '%{http_code} %{time_starttransfer} %{time_total} %{size_download}', url]).toString();
  const [code, ttfb, total, wire] = out.trim().split(' ').map(Number);
  return { code, ttfb: ttfb * 1000, total: total * 1000, wire };
}

/** Decoded body of one GET. */
const body = (url) => execFileSync('curl', [...CURL, '--compressed', url], { maxBuffer: 64 << 20 });

const median = (xs) => [...xs].sort((a, b) => a - b)[Math.floor((xs.length - 1) / 2)];
const ms = (n) => (Number.isFinite(n) ? Math.round(n).toLocaleString('en-US') : '–');
const kib = (n) => (n / 1024).toFixed(1);

const samples = new Map(); // `${site} ${route}` → timed() results, first one also has `raw`
for (let i = 0; i < SAMPLES; i++) {
  for (const route of ROUTES) {
    for (const site of sites) {
      const s = timed(site + route);
      const key = `${site} ${route}`;
      if (i === 0) s.raw = body(site + route).length;
      samples.set(key, [...(samples.get(key) ?? []), s]);
      await sleep(SPACING_MS / sites.length);
    }
  }
}

console.log(`Measured ${new Date().toISOString()}: ${SAMPLES} samples per route, ${SPACING_MS} ms apart. Times in ms.\n`);
for (const site of sites) {
  console.log(`#### ${site}\n`);
  console.log('| Route | Status | First TTFB | First total | Warm TTFB, median (min–max) | Warm total, median (min–max) | HTML KiB raw / wire |');
  console.log('|---|---|---|---|---|---|---|');
  for (const route of ROUTES) {
    const [first, ...warm] = samples.get(`${site} ${route}`) ?? [];
    if (!first) continue;
    const spread = (k) => {
      const xs = warm.map((x) => x[k]);
      return xs.length ? `${ms(median(xs))} (${ms(Math.min(...xs))}–${ms(Math.max(...xs))})` : '–';
    };
    const codes = [...new Set([first, ...warm].map((x) => x.code))].join('/');
    console.log(`| \`${route}\` | ${codes} | ${ms(first.ttfb)} | ${ms(first.total)} | ${spread('ttfb')} | ${spread('total')} | ${kib(first.raw)} / ${kib(first.wire)} |`);
  }
  console.log('');
}

console.log('#### Scripts referenced by the HTML of `/`\n');
console.log('| Site | Scripts | Compressed KiB (as served) | Raw KiB | Largest script, raw KiB |');
console.log('|---|---|---|---|---|');
for (const site of sites) {
  const html = body(site + '/').toString();
  const srcs = new Set();
  for (const m of html.matchAll(/<script[^>]*\ssrc="([^"]+)"/g)) srcs.add(m[1]);
  for (const m of html.matchAll(/<link[^>]*\sas="script"[^>]*>/g)) {
    const href = /\shref="([^"]+)"/.exec(m[0])?.[1];
    if (href) srcs.add(href);
  }
  let wire = 0;
  let raw = 0;
  let largest = 0;
  for (const src of srcs) {
    const url = new URL(src.replace(/&amp;/g, '&'), site).href;
    wire += timed(url).wire;
    const size = body(url).length;
    raw += size;
    largest = Math.max(largest, size);
    await sleep(200);
  }
  console.log(`| ${site} | ${srcs.size} | ${kib(wire)} | ${kib(raw)} | ${kib(largest)} |`);
}
