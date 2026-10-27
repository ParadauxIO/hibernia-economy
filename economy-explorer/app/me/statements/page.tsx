import Link from 'next/link';
import { Money, PageHeader, Section, TableWrap } from '@democracycraft/design-system';
import { getViewer } from '@/lib/auth/viewer';
import { buildMetadata } from '@/lib/metadata';
import { meGate } from '@/lib/services/me';
import { monthLabel } from '@/lib/services/meFlows';
import { getStatementIndex } from '@/lib/services/meStatements';
import { PartlyCovered, Updated } from '../_components/Freshness';
import { MeGateView } from '../_components/MeGateView';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Statements', description: 'Monthly statements for your personal {server} accounts.', path: '/me/statements' });
}

export default async function StatementsPage() {
  const viewer = await getViewer();
  const gate = meGate(viewer);
  if (!gate.ok) return <MeGateView kind={gate.kind} title="Statements" />;
  const { months, personal, coverage } = await getStatementIndex(viewer, gate.uuid);
  return <>
    <PageHeader title="Statements" subtitle="A statement per month for your personal accounts: opening balance, money in and out by category, closing balance. Firm and government accounts have statements on their own pages." />
    <Section title="Last 12 months" description={<>{personal.length === 1 ? personal[0]!.name : `${personal.length} personal accounts together`}. <Updated coverage={coverage} /></>}>
      <PartlyCovered coverage={coverage} />
      <TableWrap label="Monthly statements">
        <table className="data-table">
          <thead><tr>
            <th scope="col">Month</th>
            <th scope="col" className="amount">Opening</th>
            <th scope="col" className="amount">Money in</th>
            <th scope="col" className="amount">Money out</th>
            <th scope="col" className="amount">Closing</th>
          </tr></thead>
          <tbody>{months.map((m, i) => <tr key={m.month}>
            <th scope="row">
              {m.covered
                ? <Link href={`/me/statements/${m.month}`} prefetch={false}>{monthLabel(m.month)}</Link>
                : monthLabel(m.month)}
              {i === 0 && <span className="dt-sub"> · so far</span>}
            </th>
            {m.covered ? <>
              <td className="amount"><Money value={m.opening!} /></td>
              <td className="amount"><Money value={m.income!} /></td>
              <td className="amount"><Money value={m.spending!} /></td>
              <td className="amount"><Money value={m.closing!} /></td>
            </> : <td colSpan={4} className="amount muted">Preparing history</td>}
          </tr>)}</tbody>
        </table>
      </TableWrap>
    </Section>
  </>;
}
