// Redis leader lease: exactly one explorer pod runs the rollup worker.
//
//   SET ee:<tenant>:rollup:leader <holder> NX PX 30000   — acquire
//   renewed every 10 s while held (compare-and-PEXPIRE)  — keep
//   compare-and-DEL on shutdown                           — release
//
// Renew and release only touch the key while it still names this holder, so a
// pod that stalled past the TTL can never extend or delete a successor's lease.
// If the leader dies, its key expires and another pod acquires it within 30 s.

/** The subset of ioredis the lease uses, so tests can pass a fake. */
export interface LeaseRedis {
  set(key: string, value: string, px: 'PX', ttlMs: number, nx: 'NX'): Promise<'OK' | null>;
  eval(script: string, numKeys: number, ...args: (string | number)[]): Promise<unknown>;
}

export const LEASE_TTL_MS = 30_000;
export const LEASE_RENEW_MS = 10_000;

const RENEW = `if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end`;
const RELEASE = `if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end`;

export class LeaderLease {
  private holding = false;

  /**
   * @param redis null when REDIS_HOST is unset: one process, so it always leads.
   */
  constructor(
    private readonly redis: LeaseRedis | null,
    readonly key: string,
    readonly holder: string,
    private readonly ttlMs: number = LEASE_TTL_MS,
  ) {}

  get held(): boolean {
    return this.holding;
  }

  /** Take the lease if it is free, or keep it if we already hold it. */
  async acquire(): Promise<boolean> {
    if (!this.redis) return (this.holding = true);
    if (this.holding) return this.renew();
    try {
      this.holding = (await this.redis.set(this.key, this.holder, 'PX', this.ttlMs, 'NX')) === 'OK';
    } catch {
      // Redis unreachable: lead nowhere rather than risk two writers.
      this.holding = false;
    }
    return this.holding;
  }

  /** Extend the lease. Returns false (and stops holding) if it was lost. */
  async renew(): Promise<boolean> {
    if (!this.redis) return (this.holding = true);
    try {
      this.holding = Number(await this.redis.eval(RENEW, 1, this.key, this.holder, this.ttlMs)) === 1;
    } catch {
      this.holding = false;
    }
    return this.holding;
  }

  async release(): Promise<void> {
    const wasHolding = this.holding;
    this.holding = false;
    if (!this.redis || !wasHolding) return;
    try {
      await this.redis.eval(RELEASE, 1, this.key, this.holder);
    } catch {
      // The key expires on its own within the TTL.
    }
  }
}
