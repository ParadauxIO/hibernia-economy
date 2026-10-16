'use client';
// Fills the docs' live-block placeholders (see lib/docsLive.ts) with values from
// the running server, so the static pages show this server's current tax rates
// rather than hand-copied numbers. Re-runs on navigation (keyed on path) since
// the docs layout persists across page changes; pages without a live block make
// no request.

import { usePathname } from 'next/navigation';
import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { isLiveBlock, type LiveBlock } from '@/lib/docsLive';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import type { TaxRates } from '@/lib/taxRates';

type Target = { el: HTMLElement; name: LiveBlock };
type Rates = TaxRates | 'error' | null;

const UNAVAILABLE = "The current rates aren't available right now.";

function Status({ children }: { children: React.ReactNode }) {
  return <p className="doc-live-status">{children}</p>;
}

function BalanceTax({ rates }: { rates: TaxRates['balanceTax'] }) {
  if (!rates) return <Status>{UNAVAILABLE}</Status>;
  return (
    <>
      {!rates.enabled && <Status>Balance tax is currently switched off on this server.</Status>}
      <table>
        <thead><tr><th>Balance</th><th>Weekly rate</th></tr></thead>
        <tbody>
          {rates.brackets.map((b) => (
            <tr key={b.band}><td>{b.band}</td><td>{b.rate}</td></tr>
          ))}
        </tbody>
      </table>
      <Status>Live from this server · last changed <LocalTimestamp value={rates.updatedAt} /></Status>
    </>
  );
}

function IncomeTax({ rates }: { rates: TaxRates['incomeTax'] }) {
  if (!rates) return <Status>{UNAVAILABLE}</Status>;
  return (
    <>
      {!rates.enabled && <Status>Income tax is currently switched off on this server.</Status>}
      <table>
        <thead><tr><th>Income from</th><th>Rate</th></tr></thead>
        <tbody>
          {rates.sources.map((s) => (
            <tr key={s.plugin}><td><code>{s.plugin}</code></td><td>{s.rate}</td></tr>
          ))}
          <tr><td>{rates.sources.length ? 'Anything else' : 'All taxed income'}</td><td>{rates.defaultRate}</td></tr>
        </tbody>
      </table>
      <Status>Live from this server · last changed <LocalTimestamp value={rates.updatedAt} /></Status>
    </>
  );
}

function Block({ name, rates }: { name: LiveBlock; rates: Rates }) {
  if (rates === null) return <Status>Loading the current rates…</Status>;
  if (rates === 'error') return <Status>{UNAVAILABLE}</Status>;
  return name === 'balance-tax' ? <BalanceTax rates={rates.balanceTax} /> : <IncomeTax rates={rates.incomeTax} />;
}

export function LiveDocBlocks() {
  const path = usePathname();
  const [targets, setTargets] = useState<Target[]>([]);
  const [rates, setRates] = useState<Rates>(null);

  useEffect(() => {
    const found = Array.from(document.querySelectorAll<HTMLElement>('.doc-prose [data-live]')).flatMap((el) => {
      const name = el.dataset.live;
      return isLiveBlock(name) ? [{ el, name }] : [];
    });
    // Drop the static "loading" text; React renders the block's content in its place.
    found.forEach((t) => t.el.replaceChildren());
    // The portal targets are server-rendered placeholders that only exist in the
    // DOM after commit, so reading them is inherently an effect.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setTargets(found);
    if (!found.length) return;

    let cancelled = false;
    fetch('/api/docs/tax-rates')
      .then((r) => (r.ok ? (r.json() as Promise<TaxRates>) : Promise.reject(new Error(String(r.status)))))
      .then((d) => { if (!cancelled) setRates(d); })
      .catch(() => { if (!cancelled) setRates('error'); });
    return () => { cancelled = true; };
  }, [path]);

  return <>{targets.map((t, i) => createPortal(<Block name={t.name} rates={rates} />, t.el, `${path}:${i}`))}</>;
}
