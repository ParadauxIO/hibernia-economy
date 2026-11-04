/** Pure evaluation contract, shared by tests and the server-only runtime. */
export const FLAG_REFRESH_MS = 5_000;
export const FLAG_MAX_AGE_MS = 30_000;
export const validFlagKey = (key: string) => key.length <= 128 && key === key.trim() && /^[a-z][a-z0-9_]*\.[a-z][a-z0-9_.]*$/.test(key);
export type FlagValue = { name: string; enabled: unknown };

export class FeatureFlagSnapshot {
  private values = new Set<string>();
  private loadedAt: number | null = null;
  constructor(private readonly now: () => number = () => performance.now()) {}
  publish(rows: readonly FlagValue[], startedAt: number) {
    this.values = new Set(rows.filter(row => validFlagKey(row.name) && row.enabled === 1).map(row => row.name));
    this.loadedAt = startedAt;
  }
  isEnabled(key: string): boolean {
    const age = this.loadedAt === null ? Infinity : this.now() - this.loadedAt;
    return validFlagKey(key) && age >= 0 && age < FLAG_MAX_AGE_MS && this.values.has(key);
  }
}

/** Demand-refreshed per-process cache. Concurrent requests share one bounded wait. */
export class FeatureFlagCache {
  private readonly snapshot: FeatureFlagSnapshot;
  private nextRefresh = -Infinity;
  private inFlight: Promise<void> | null = null;
  private failing = false;
  constructor(private readonly load: () => Promise<readonly FlagValue[]>,
    private readonly now: () => number = () => performance.now(),
    private readonly log: (message: string) => void = message => console.warn(message)) {
    this.snapshot = new FeatureFlagSnapshot(now);
  }
  async isEnabled(key: string): Promise<boolean> {
    if (!validFlagKey(key)) return false;
    if (this.now() >= this.nextRefresh && !this.inFlight) {
      const started = this.now();
      this.nextRefresh = started + FLAG_REFRESH_MS;
      this.inFlight = Promise.resolve().then(this.load).then(rows => {
        this.snapshot.publish(rows, started);
        if (this.failing) this.log('Feature flag refresh recovered.');
        this.failing = false;
      }).catch(() => {
        if (!this.failing) this.log('Feature flag refresh failed; enabled values expire after 30 seconds.');
        this.failing = true;
      }).finally(() => { this.inFlight = null; });
    }
    if (this.inFlight) {
      let timer: ReturnType<typeof setTimeout> | undefined;
      await Promise.race([this.inFlight, new Promise<void>(resolve => { timer = setTimeout(resolve, 3_000); })]);
      clearTimeout(timer);
    }
    return this.snapshot.isEnabled(key);
  }
}
