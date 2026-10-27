import Link from 'next/link';
import { Money, PageHeader, Section, TableWrap, Tag, When } from '@democracycraft/design-system';
import { getViewer } from '@/lib/auth/viewer';
import { buildMetadata } from '@/lib/metadata';
import { meGate } from '@/lib/services/me';
import { monthLabel } from '@/lib/services/meFlows';
import { getMyFirmsPage } from '@/lib/services/meFirms';
import { Updated } from '../_components/Freshness';
import { MeGateView } from '../_components/MeGateView';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'My firms', description: 'Firms you own or work for on {server}, with your role.', path: '/me/firms' });
}

const PERMISSION_LABELS: Record<string, string> = { ADMIN: 'Admin', FINANCIAL: 'Finances', CHESTSHOP: 'Shops', DEFAULT: 'Member' };

export default async function MyFirmsPage() {
  const viewer = await getViewer();
  const gate = meGate(viewer);
  if (!gate.ok) return <MeGateView kind={gate.kind} title="My firms" />;
  const { firms, month, coverage } = await getMyFirmsPage(viewer, gate.uuid);
  const finance = firms.filter((f) => f.finance);

  return <>
    <PageHeader title="Firms" subtitle="Firms you own or work for. A firm’s money is the firm’s, not yours: it is never added to your balance." />
    <Section title="Your roles">
      {firms.length === 0 ? <p className="me-empty">You don’t own or work for a firm. <Link href="/docs/guides/setting-up-a-business" prefetch={false}>Setting up a business</Link></p> : <TableWrap label="Your firms">
        <table className="data-table">
          <thead><tr>
            <th scope="col">Firm</th>
            <th scope="col">Your role</th>
            <th scope="col">Permissions</th>
            <th scope="col">Hired</th>
          </tr></thead>
          <tbody>{firms.map((f) => <tr key={f.firmId}>
            <th scope="row">
              <Link href={`/firms/${encodeURIComponent(f.name)}`} prefetch={false}>{f.name}</Link>
              {f.archived && <span className="dt-sub"> · disbanded</span>}
            </th>
            <td>{f.isProprietor ? <>Proprietor{f.role ? <span className="dt-sub"> · {f.role}</span> : null}</> : f.role ?? '—'}</td>
            <td><span className="me-relations">{f.isProprietor && <Tag tone="accent">Everything</Tag>}{f.permissions.map((p) => <Tag key={p}>{PERMISSION_LABELS[p] ?? p}</Tag>)}</span></td>
            <td>{f.hiredAt ? <When value={f.hiredAt} /> : f.isProprietor ? <span className="muted">Founder</span> : '—'}
              {f.firstJoinedAt && f.hiredAt && f.firstJoinedAt < f.hiredAt && <span className="dt-sub"> · first joined <When value={f.firstJoinedAt} /></span>}</td>
          </tr>)}</tbody>
        </table>
      </TableWrap>}
    </Section>

    {finance.length > 0 && <Section title="Firm finances" divider
      description={<>The firm’s money, for firms where you have a finance role. {monthLabel(month)} so far, transfers between the firm’s own accounts left out. <Updated coverage={coverage} /></>}>
      <TableWrap label="Firm finances">
        <table className="data-table">
          <thead><tr>
            <th scope="col">Firm</th>
            <th scope="col" className="amount">Firm’s balance</th>
            <th scope="col" className="amount">In this month</th>
            <th scope="col" className="amount">Out this month</th>
            <th scope="col" className="amount">Net this month</th>
          </tr></thead>
          <tbody>{finance.map((f) => <tr key={f.firmId}>
            <th scope="row">
              <Link href={`/firms/${encodeURIComponent(f.name)}?tab=financials`} prefetch={false}>{f.name}</Link>
              <span className="dt-sub"> · {f.finance!.accountCount} account{f.finance!.accountCount === 1 ? '' : 's'}</span>
            </th>
            <td className="amount"><Money value={f.finance!.balance} /></td>
            <td className="amount">{f.finance!.monthIn !== null ? <Money value={f.finance!.monthIn} /> : '—'}</td>
            <td className="amount">{f.finance!.monthOut !== null ? <Money value={f.finance!.monthOut} /> : '—'}</td>
            <td className="amount">{f.finance!.monthNet !== null ? <Money value={f.finance!.monthNet} sign="exceptZero" tone="auto" /> : '—'}</td>
          </tr>)}</tbody>
        </table>
      </TableWrap>
      {!coverage.covered && <p className="me-note">Preparing history: this month’s figures appear once it is covered.</p>}
    </Section>}
  </>;
}
