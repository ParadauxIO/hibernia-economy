/**
 * Start the in-process rollup worker (PAR-532, spec §4.2). Called once from
 * instrumentation.ts register() in the Node runtime. A no-op until the rollup
 * workstream fills it in; it owns this file and everything under lib/rollup/.
 */
export function startRollupWorker(): void {}
