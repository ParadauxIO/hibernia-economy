import { beforeEach, describe, expect, it, vi } from 'vitest';
import { peekServerStatus, resetServerStatusForTests, type ServerStatus } from '@/lib/serverStatus';

const ONLINE: ServerStatus = { online: true, players: 42, max: 100 };

beforeEach(() => resetServerStatusForTests());

describe('peekServerStatus', () => {
  it('never waits: the first call returns null and starts one background fetch', async () => {
    let resolve!: (s: ServerStatus) => void;
    const fetchStatus = vi.fn(() => new Promise<ServerStatus>((r) => { resolve = r; }));
    expect(peekServerStatus('play.example', 0, fetchStatus)).toBeNull();
    expect(peekServerStatus('play.example', 0, fetchStatus)).toBeNull();
    expect(fetchStatus).toHaveBeenCalledTimes(1); // concurrent requests share the in-flight fetch
    resolve(ONLINE);
    await vi.waitFor(() => expect(peekServerStatus('play.example', Date.now(), fetchStatus)).toEqual(ONLINE));
  });

  it('serves the last answer and refreshes at most once a minute', async () => {
    const fetchStatus = vi.fn(async () => ONLINE);
    peekServerStatus('play.example', 0, fetchStatus);
    await vi.waitFor(() => expect(peekServerStatus('play.example', Date.now(), fetchStatus)).toEqual(ONLINE));
    expect(fetchStatus).toHaveBeenCalledTimes(1);
    const later = Date.now() + 61_000;
    fetchStatus.mockResolvedValue({ online: true, players: 7, max: 100 });
    expect(peekServerStatus('play.example', later, fetchStatus)).toEqual(ONLINE); // stale value now, refresh in background
    expect(fetchStatus).toHaveBeenCalledTimes(2);
  });
});
