import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { searchGlobal } from '@/lib/sql/search';
import { fmtAmtFull, shortenUuid } from '@/lib/format';

const SP_SCHEMA = z.object({
  q: z.string().trim().min(1).optional(),
  limit: z.coerce.number().int().min(1).max(50).default(10),
});

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "Search", description: "Search accounts, players, firms and items across the {server} economy.", path: "/search" });
}

export default async function SearchPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));

  const results = sp.q ? await searchGlobal(sp.q, sp.limit) : [];
  const grouped = {
    account: results.filter((r) => r.kind === 'account'),
    firm: results.filter((r) => r.kind === 'firm'),
    player: results.filter((r) => r.kind === 'player'),
  };

  return (
    <>
      <div className="page-heading">
        <h1>Search</h1>
        {sp.q && <span className="sub">results for &ldquo;{sp.q}&rdquo;</span>}
      </div>

      <form className="toolbar" style={{ marginBottom: 18 }}>
        <input
          name="q"
          defaultValue={sp.q ?? ''}
          placeholder="Search accounts, firms, players…"
          className="toolbar-search"
        />
        <button type="submit" className="btn">Search</button>
      </form>

      {!sp.q && (
        <div className="card">
          <div className="card-title">Search the economy</div>
          <p style={{ padding: '4px 4px 8px', color: 'var(--fg-soft)' }}>
            Type an account name, firm name, player name, or numeric account ID.
          </p>
        </div>
      )}

      {sp.q && results.length === 0 && (
        <div className="card">
          <div className="card-title">No results</div>
        </div>
      )}

      {grouped.account.length > 0 && (
        <ResultGroup title={`Accounts (${grouped.account.length})`}>
          {grouped.account.map((r, i) => (
            <Link
              key={`a-${r.account_id}-${i}`}
              href={`/accounts/${r.account_id}` as Route}
              className="result-row"
              prefetch={false}
            >
              <span style={{ fontWeight: 500 }}>{r.label}</span>
              {r.account_type && <span className={`badge badge-${r.account_type}`}>{r.account_type}</span>}
              {r.balance && <span className="amount neutral" style={{ marginLeft: 'auto' }}>{fmtAmtFull(r.balance)}</span>}
            </Link>
          ))}
        </ResultGroup>
      )}

      {grouped.firm.length > 0 && (
        <ResultGroup title={`Firms (${grouped.firm.length})`}>
          {grouped.firm.map((r, i) => (
            <Link
              key={`f-${r.firm_id}-${i}`}
              href={`/firms/${encodeURIComponent(r.firm_name ?? r.label)}` as Route}
              className="result-row"
              prefetch={false}
            >
              <span style={{ fontWeight: 500 }}>{r.label}</span>
              {r.secondary && <span className="muted small">{r.secondary}</span>}
            </Link>
          ))}
        </ResultGroup>
      )}

      {grouped.player.length > 0 && (
        <ResultGroup title={`Players (${grouped.player.length})`}>
          {grouped.player.map((r, i) => (
            <div key={`p-${r.player_uuid ?? i}`} className="result-row">
              <span style={{ fontWeight: 500 }}>{r.player_name ?? r.label}</span>
              {r.player_uuid && (
                <span className="mono muted small" title={r.player_uuid}>{shortenUuid(r.player_uuid)}</span>
              )}
              {r.secondary && <span className="muted small" style={{ marginLeft: 'auto' }}>seen {r.secondary}</span>}
            </div>
          ))}
        </ResultGroup>
      )}
    </>
  );
}

function ResultGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="card">
      <div className="card-title">{title}</div>
      <div style={{ display: 'flex', flexDirection: 'column' }}>{children}</div>
    </div>
  );
}

