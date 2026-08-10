import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import type { Route } from 'next';
import { auditView } from '@/lib/audit';
import { getViewer } from '@/lib/auth/viewer';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin', description: 'Admin tools for the {server} economy.', path: '/admin' });
}

// The layout already gated to admin; this is the landing for the admin section.
const TOOLS: { href: Route; title: string; desc: string }[] = [
  { href: '/admin/players', title: 'Players', desc: 'Look up any player and view their personal data dashboard (balances, transactions, counterparties).' },
  { href: '/admin/accounts', title: 'Accounts', desc: 'Move money between accounts, rename, change owner, archive/unarchive — ledger-safe, audited.' },
  { href: '/transactions', title: 'Transactions', desc: 'The whole-economy transaction firehose — powerful filters over every ledger transfer.' },
  { href: '/admin/api-keys', title: 'API keys', desc: 'Programmatic API keys, usage, and per-issuer rate-limit overrides.' },
  { href: '/admin/groups', title: 'Groups', desc: 'RBAC access groups and capabilities (manual + LuckPerms-fed).' },
  { href: '/admin/webhooks', title: 'Webhooks', desc: 'Every transaction-feed webhook — search, manage, and register for any account.' },
  { href: '/admin/firms', title: 'Firms', desc: 'Disband or rename a firm — destructive, ledger-safe, audited (separate from the public firm page).' },
];

export default async function AdminHomePage() {
  const viewer = await getViewer();
  await auditView(viewer, { path: '/admin', targetType: 'global' });

  return (
    <>
      <div className="page-heading">
        <h1>Admin</h1>
        <span className="sub">tools for managing the {''}economy explorer</span>
      </div>

      <div className="admin-tool-grid">
        {TOOLS.map((t) => (
          <Link key={t.href} href={t.href} className="card admin-tool-card" prefetch={false}>
            <div className="card-title">{t.title} →</div>
            <p className="muted small" style={{ margin: 0 }}>{t.desc}</p>
          </Link>
        ))}
      </div>
    </>
  );
}
