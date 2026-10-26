import { parseMoney, toDbString } from '@/lib/money';
import type { FlowLine } from '@/lib/services/governmentModel';
import type { GovScope } from '@/lib/services/governmentScope';

export interface UnitRow { key: string; name: string; values: Record<string, string>; total: string }

/**
 * Pivots revenue or spending lines into one row per unit, with a column per
 * group (`taxes`, `fines`, `other`…). Unit keys are each account's own unit,
 * so a sub-unit is its own row.
 */
export function linesByUnit(scope: GovScope, lines: readonly FlowLine[], group: (l: FlowLine) => string): UnitRow[] {
  const names = new Map<string, string>([['unassigned', 'Unassigned']]);
  for (const u of scope.unitOf.values()) names.set(u.slug, u.name);
  const rows = new Map<string, Map<string, bigint>>();
  for (const l of lines) {
    const g = group(l);
    for (const [unit, amount] of Object.entries(l.byUnit)) {
      const row = rows.get(unit) ?? new Map<string, bigint>();
      row.set(g, (row.get(g) ?? 0n) + parseMoney(amount));
      rows.set(unit, row);
    }
  }
  return [...rows].map(([key, m]) => ({
    key,
    name: names.get(key) ?? key,
    values: Object.fromEntries([...m].map(([g, v]) => [g, toDbString(v)])),
    total: toDbString([...m.values()].reduce((s, v) => s + v, 0n)),
  })).sort((a, b) => {
    const x = parseMoney(a.total);
    const y = parseMoney(b.total);
    return x > y ? -1 : x < y ? 1 : a.name.localeCompare(b.name);
  });
}
