import { EventEmitter } from 'node:events';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
const mock = vi.hoisted(() => ({ instance: null as (EventEmitter & { status: string }) | null }));
vi.mock('ioredis', () => ({ default: class { constructor() { return mock.instance!; } } }));
beforeEach(() => {
  vi.resetModules(); vi.useFakeTimers(); vi.stubEnv('REDIS_HOST', 'localhost');
  mock.instance = Object.assign(new EventEmitter(), { status: 'connecting' });
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllEnvs(); });
it('waits for initial readiness before allowing recovery and removes temporary listeners', async () => {
  const { waitForRedisReady } = await import('@/lib/redis');
  const waiting = waitForRedisReady();
  let ready = false; void waiting.then(() => { ready = true; });
  await Promise.resolve(); expect(ready).toBe(false);
  mock.instance!.status = 'ready'; mock.instance!.emit('ready');
  await waiting; expect(ready).toBe(true);
  expect(mock.instance!.listenerCount('ready')).toBe(0);
  expect(vi.getTimerCount()).toBe(0);
  await waitForRedisReady(); expect(vi.getTimerCount()).toBe(0);
});
it('fails closed after the bounded connection timeout', async () => {
  const { waitForRedisReady } = await import('@/lib/redis');
  const waiting = expect(waitForRedisReady()).rejects.toThrow('Redis unavailable');
  await vi.advanceTimersByTimeAsync(2000); await waiting;
  expect(mock.instance!.listenerCount('ready')).toBe(0);
});
it('fails closed when Redis is not configured', async () => {
  vi.stubEnv('REDIS_HOST', '');
  const { waitForRedisReady } = await import('@/lib/redis');
  await expect(waitForRedisReady()).rejects.toThrow('Redis unavailable');
});
