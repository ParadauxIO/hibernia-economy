import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { searchPlayers } from '@/lib/sql/me';
import { fmtN } from '@/lib/format';
import { Player } from '@/components/Player';
import { Toolbar } from '@/components/Toolbar';

export const dynamic = 'force-dynamic';

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const SP_SCHEMA = z.object({ q: z.string().trim().min(1).optional() });

export async function generateMetadata() {
  return buildMetadata({ title: 'Players', description: 'Admin: look up a player and view their personal {server} economy dashboard.', path: '/admin/players' });
}

export default async function AdminPlayersPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  // The /admin layout gates the whole section to admins; audit the access here.
  const viewer = await getViewer();
  await auditView(viewer, { path: '/admin/players', targetType: 'global' });

  const sp = SP_SCHEMA.safeParse(flattenSearchParams(await searchParams)).data ?? {};
  const q = sp.q ?? null;
  const results = q ? await searchPlayers(q, 50) : [];
  const directUuid = q && UUID_RE.test(q) ? q.toLowerCase() : null;

  return (
    <>
      <div className="page-heading">
        <h1>Players</h1>
        <span className="sub">look up a player to view their personal data dashboard</span>
      </div>

      <div className="card">
        <div className="card-title">Find a player</div>
        <Toolbar searchPlaceholder="Search by name, or paste a UUID…" />
        {directUuid && (
          <p className="muted small" style={{ marginTop: 8, marginBottom: 0 }}>
            Looks like a UUID — <Link href={`/admin/players/${directUuid}` as Route} className="rowlink">view this player&apos;s data →</Link>
          </p>
        )}
        <div className="table-wrap" style={{ marginTop: 12 }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>Player</th>
                <th>UUID</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {!q && <tr><td colSpan={3} className="empty">Search for a player above.</td></tr>}
              {q && results.length === 0 && <tr><td colSpan={3} className="empty">No players match “{q}”.</td></tr>}
              {results.map((p) => (
                <tr key={p.uuid}>
                  <td><Player name={p.name} uuid={p.uuid} link={false} /></td>
                  <td className="mono muted small">{p.uuid}</td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <Link href={`/admin/players/${p.uuid}` as Route} className="btn" prefetch={false}>View data</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {results.length > 0 && <p className="muted small" style={{ marginTop: 8, marginBottom: 0 }}>{fmtN(results.length)} match(es) (capped at 50).</p>}
      </div>
    </>
  );
}

