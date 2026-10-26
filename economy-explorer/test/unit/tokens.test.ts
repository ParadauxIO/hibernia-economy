import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// Design tokens hold in all four theme × mode combinations. The stylesheet is
// parsed directly, so a new colour that fails contrast in one block fails here
// before anyone has to spot it in a screenshot.

const css = readFileSync(resolve(process.cwd(), 'packages/design-system/styles/tokens.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

function block(selector: string): Record<string, string> {
  const at = css.indexOf(`${selector} {`);
  if (at < 0) throw new Error(`missing block ${selector}`);
  const body = css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
  return Object.fromEntries([...body.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)].map((m) => [m[1]!, m[2]!.trim()]));
}

const A = block(':root');
const B = block(':root[data-mode="light"]');
const C = block(':root[data-theme="red"]');
const D = block(':root[data-theme="red"][data-mode="light"]');

const COMBOS = {
  'blue · dark': { ...A },
  'blue · light': { ...A, ...B },
  'red · dark': { ...A, ...C },
  // B and C have equal specificity; C comes later, then D overrides.
  'red · light': { ...A, ...B, ...C, ...D },
} as const;

function resolveVar(tokens: Record<string, string>, value: string, depth = 0): string {
  const m = /^var\((--[\w-]+)(?:,[^)]*)?\)$/.exec(value);
  if (!m) return value;
  if (depth > 5) throw new Error(`var cycle at ${value}`);
  const next = tokens[m[1]!];
  if (next === undefined) throw new Error(`undefined token ${m[1]}`);
  return resolveVar(tokens, next, depth + 1);
}

function luminance(hex: string): number {
  const h = hex.replace('#', '');
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function contrast(a: string, b: string): number {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p) as [number, number];
  return (x + 0.05) / (y + 0.05);
}

const TEXT = ['--fg', '--fg-soft', '--fg-muted', '--accent', '--pos', '--neg', '--good', '--warn', '--bad'];
const GRAPHIC = [...Array.from({ length: 10 }, (_, i) => `--cat-${i + 1}`), '--seq-5'];
const SURFACES = ['--bg', '--bg-soft', '--bg-card', '--bg-elev'];

describe('design tokens', () => {
  it('redefines every red · dark token in red · light, and blue · light covers them too', () => {
    // Otherwise a red · dark value leaks into red · light (C beats B on source order).
    expect(Object.keys(C).filter((k) => !(k in D))).toEqual([]);
    expect(Object.keys(C).filter((k) => !(k in B))).toEqual([]);
  });

  it('keeps every legacy token name', () => {
    const legacy = ['--bg', '--bg-soft', '--bg-card', '--bg-elev', '--border', '--border-soft', '--fg', '--fg-soft', '--fg-muted', '--fg-faint',
      '--good', '--warn', '--bad', '--info', '--accent', '--accent-2', '--accent-soft', '--accent-line', '--accent-fill', '--brand-bg', '--brand-bg-2',
      '--brand-fg', '--brand-fg-muted', '--brand-edge', '--mono', '--sans', '--radius', '--radius-md', '--radius-lg', '--t-fast', '--t-med', '--section-gap',
      ...Array.from({ length: 7 }, (_, i) => `--c-${i + 1}`)];
    expect(legacy.filter((k) => !(k in A))).toEqual([]);
  });

  it('defines the v2 scales', () => {
    for (const size of ['xs', 'sm', 'md', 'lg', 'xl', '2xl', '3xl']) {
      expect(A[`--text-${size}`]).toMatch(/px$/);
      expect(A[`--leading-${size}`]).toMatch(/px$/);
    }
    expect([1, 2, 3, 4, 5, 6, 8, 10, 12, 16].map((n) => A[`--space-${n}`])).toEqual(['4px', '8px', '12px', '16px', '20px', '24px', '32px', '40px', '48px', '64px']);
    expect(A['--section-gap']).toBe('var(--space-6)');
    expect([A['--radius-sm'], A['--radius-md'], A['--radius-lg'], A['--radius-pill']]).toEqual(['6px', '10px', '14px', '999px']);
  });

  for (const [name, tokens] of Object.entries(COMBOS)) {
    describe(name, () => {
      const surfaces = SURFACES.map((s) => resolveVar(tokens, tokens[s]!));
      it.each(TEXT)('%s is readable text (4.5:1) on every surface', (token) => {
        const colour = resolveVar(tokens, tokens[token]!);
        for (const surface of surfaces) expect(contrast(colour, surface), `${token} ${colour} on ${surface}`).toBeGreaterThanOrEqual(4.5);
      });
      it.each(GRAPHIC)('%s is a visible graphic (3:1) on cards', (token) => {
        const colour = resolveVar(tokens, tokens[token]!);
        expect(contrast(colour, resolveVar(tokens, tokens['--bg-card']!))).toBeGreaterThanOrEqual(3);
      });
      it('puts white text on the filled accent at 4.5:1', () => {
        expect(contrast(resolveVar(tokens, tokens['--on-accent']!), resolveVar(tokens, tokens['--accent-fill']!))).toBeGreaterThanOrEqual(4.5);
      });
    });
  }

  it('keeps green out of the red brand palette', () => {
    for (const tokens of [COMBOS['red · dark'], COMBOS['red · light']]) {
      for (const k of [...GRAPHIC, '--pos']) {
        const h = resolveVar(tokens, tokens[k]!).replace('#', '');
        const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
        // "Green" = green channel clearly dominant over both red and blue.
        expect(g! > r! + 24 && g! > b! + 24, `${k} #${h} is green`).toBe(false);
      }
    }
  });
});
