import { describe, it, expect, vi, beforeEach } from 'vitest';

const rollupHealth = vi.fn();
vi.mock('@/lib/services/rollup', () => ({ rollupHealth }));

import { GET } from '@/app/healthz/route';

describe('/healthz', () => {
  beforeEach(() => { rollupHealth.mockReset(); });

  it('stays a plain 200 "ok" that never touches the database', async () => {
    const res = await GET(new Request('http://explorer.test/healthz'));
    expect(res.status).toBe(200);
    expect(await res.text()).toBe('ok');
    expect(rollupHealth).not.toHaveBeenCalled();
  });

  it('?rollup=1 reports worker state and rollup health', async () => {
    rollupHealth.mockResolvedValue({ lastSuccessAt: '2026-10-26T04:00:00.000Z', stale: false, coverage: {} });
    const res = await GET(new Request('http://explorer.test/healthz?rollup=1'));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({
      status: 'ok',
      // Off under Vitest by default (ROLLUP_WORKER unset).
      worker: { enabled: false, started: false, leader: false },
      rollup: { lastSuccessAt: '2026-10-26T04:00:00.000Z', stale: false, coverage: {} },
    });
  });

  it('?rollup=1 is still 200 when the database is unavailable, without leaking its error', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    rollupHealth.mockImplementation(async () => { throw new Error('connect ECONNREFUSED 10.0.0.5:3306'); });
    try {
      const res = await GET(new Request('http://explorer.test/healthz?rollup=1'));
      expect(res.status).toBe(200);
      const body = await res.json();
      expect(body.rollup).toEqual({ unavailable: true });
      expect(JSON.stringify(body)).not.toContain('10.0.0.5');
    } finally { warn.mockRestore(); }
  });
});
