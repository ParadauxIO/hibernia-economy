'use client';

import { useId, useTransition } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';

export interface UnitOption { value: string; label: string; depth: number }

/** The unit selector every /gov page shares. Writes `?unit=` and drops pagination. */
export function UnitSelect({ value, options }: { value: string; options: UnitOption[] }) {
  const router = useRouter();
  const params = useSearchParams();
  const [pending, start] = useTransition();
  const id = useId();
  return <div className="gov-unit-select" aria-busy={pending || undefined}>
    <label htmlFor={id} className="field-label">Unit</label>
    <select id={id} className="select" value={value} onChange={(e) => {
      const next = new URLSearchParams(params.toString());
      for (const key of ['page', 'cursor', 'vcursor']) next.delete(key);
      if (e.target.value === 'all') next.delete('unit'); else next.set('unit', e.target.value);
      const qs = next.toString();
      start(() => router.push(`?${qs}`, { scroll: false }));
    }}>
      {options.map((o) => <option key={o.value} value={o.value}>{`${' '.repeat(o.depth)}${o.label}`}</option>)}
    </select>
  </div>;
}
