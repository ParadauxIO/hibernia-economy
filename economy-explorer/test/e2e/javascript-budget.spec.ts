import { brotliCompressSync } from 'node:zlib';
import { test, expect } from '@playwright/test';
import budget from '../../ops/javascript-budget.json';

// Count modern-browser initial scripts from the production HTML. Deferred chart
// chunks and nomodule-only polyfills are not downloaded for first paint.
for (const route of budget.routes) {
  test(`initial JavaScript budget: ${route}`, async ({ request }, testInfo) => {
    test.skip(testInfo.project.name !== 'chromium', 'identical assets on mobile');
    const response = await request.get(route);
    expect(response.ok()).toBe(true);
    const html = await response.text();
    const sources = new Set([...html.matchAll(/<script\b[^>]*>/gi)]
      .filter(([tag]) => !/\bnomodule\b/i.test(tag))
      .map(([tag]) => /\bsrc="([^"]+)"/.exec(tag)?.[1])
      .filter((src): src is string => !!src?.startsWith('/_next/static/')));
    expect(sources.size).toBeGreaterThan(0);
    let bytes = 0;
    for (const source of sources) {
      const asset = await request.get(source);
      expect(asset.ok()).toBe(true);
      const body = await asset.body();
      expect(body.toString()).not.toContain('recharts-wrapper');
      bytes += brotliCompressSync(body).length;
    }
    await testInfo.attach('initial-javascript', { body: JSON.stringify({ route, compression: budget.compression, bytes, limit: budget.maxInitialKiB * 1024 }), contentType: 'application/json' });
    expect(bytes).toBeLessThanOrEqual(budget.maxInitialKiB * 1024);
  });
}
