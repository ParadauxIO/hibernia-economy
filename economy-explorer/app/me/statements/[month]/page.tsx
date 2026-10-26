import Link from 'next/link';
import type { Route } from 'next';
import { notFound } from 'next/navigation';
import { MetricCard, MetricRow, Money, PageHeader, Section, Segmented, TableWrap } from '@democracycraft/design-system';
import { Waterfall } from '@/components/charts/AreaTimeline';
import { CsvButton } from '@/components/CsvButton';
import { getViewer } from '@/lib/auth/viewer';
import { NotFoundError } from '@/lib/errors';
import { buildMetadata } from '@/lib/metadata';
import { meGate } from '@/lib/services/me';
import { addMonths, monthLabel, taxKindLabel } from '@/lib/services/meFlows';
import { getStatement, parseMonth, type Statement } from '@/lib/services/meStatements';
import { FeedTable } from '../../_components/FeedTable';
import { Preparing, Updated } from '../../_components/Freshness';
import { MeGateView } from '../../_components/MeGateView';
import { PrintButton } from '../../_components/PrintButton';

export const dynamic = 'force-dynamic';

export async function generateMetadata({ params }: { params: Promise<{ month: string }> }) {
  const { month } = await params;
  return buildMetadata({ title: 'Statement', description: 'A monthly statement for your personal {server} accounts.', path: `/me/statements/${month}` });
}

async function load(viewer: Awaited<ReturnType<typeof getViewer>>, uuid: string, month: string, accountId: number | null): Promise<Statement> {
  try {
    return await getStatement(viewer, uuid, month, accountId);
  } catch (e) {
    if (e instanceof NotFoundError) notFound();
    throw e;
  }
}

export default async function StatementPage({ params, searchParams }: {
  params: Promise<{ month: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const viewer = await getViewer();
  const gate = meGate(viewer);
  if (!gate.ok) return <MeGateView kind={gate.kind} title="Statements" />;
  const { month: raw } = await params;
  const month = parseMonth(raw);
  if (!month) notFound();
  const sp = await searchParams;
  const accountParam = typeof sp.account === 'string' && /^\d{1,10}$/.test(sp.account) ? Number(sp.account) : null;
  const s = await load(viewer, gate.uuid, month, accountParam);
  const label = monthLabel(month);
  const scope = s.account ? s.account.name : s.personal.length === 1 ? s.personal[0]!.name : 'All your personal accounts';
  const qs = s.account ? `?account=${s.account.id}` : '';
  const prev = addMonths(month, -1);
  const next = addMonths(month, 1);
  const hasNext = parseMonth(next) !== null;
  const ins = s.steps.filter((x) => x.direction === 'in' && !x.internal);
  const outs = s.steps.filter((x) => x.direction === 'out' && !x.internal);
  const moves = s.steps.filter((x) => x.internal);

  const csvRows: (string | number)[][] = [
    ['Opening balance', '', s.opening],
    ...ins.map((x) => ['Money in', x.label, x.value]),
    ...outs.map((x) => ['Money out', x.label, x.value]),
    ...moves.map((x) => ['Between your accounts', x.label, x.value]),
    ['Closing balance', '', s.closing],
    ['Tax paid', '', s.taxPaid],
    ...s.largest.map((r) => ['Largest transaction', `#${r.txnId} ${r.description}`, r.amount]),
  ];

  return <div className="me-statement">
    <PageHeader eyebrow={<Link href="/me/statements" prefetch={false}>Statements</Link>} title={`Statement · ${label}`}
      subtitle={<>{scope}{s.partial ? ' · month so far' : ''}</>}
      actions={<span className="me-no-print toolbar-export">
        <PrintButton />
        <CsvButton label="CSV" filename={`statement-${month}${s.account ? `-${s.account.id}` : ''}.csv`} headers={['Section', 'Item', 'Amount']} rows={csvRows} />
      </span>} />

    <div className="me-statement-meta me-no-print">
      <Link href={`/me/statements/${prev}${qs}` as Route} prefetch={false}>← {monthLabel(prev)}</Link>
      {hasNext && <Link href={`/me/statements/${next}${qs}` as Route} prefetch={false}>{monthLabel(next)} →</Link>}
      <Updated coverage={s.coverage} />
    </div>

    {s.personal.length > 1 && <div className="me-no-print">
      <Segmented label="Accounts" value={s.account ? String(s.account.id) : 'all'} items={[
        { value: 'all', label: 'All personal accounts', href: `/me/statements/${month}` },
        ...s.personal.map((a) => ({ value: String(a.id), label: a.name, href: `/me/statements/${month}?account=${a.id}` })),
      ]} />
    </div>}

    {!s.coverage.covered ? <Preparing coverage={s.coverage} what={label} /> : <>
      <MetricRow label="Statement summary">
        <MetricCard label="Opening balance" value={<Money value={s.opening} />} />
        <MetricCard label="Money in" value={<Money value={s.income} />} meta="Transfers between your own accounts left out" />
        <MetricCard label="Money out" value={<Money value={s.spending} />} />
        <MetricCard label={s.partial ? 'Balance so far' : 'Closing balance'} value={<Money value={s.closing} />} />
      </MetricRow>

      <Section title="Opening to closing" description="Money in and out by category.">
        <Waterfall label={`${label} statement`} opening={s.opening} closing={s.closing} openingLabel="Opening" closingLabel={s.partial ? 'So far' : 'Closing'}
          steps={s.steps.map((x) => ({ key: x.key, label: x.label, value: x.value }))} empty="No money moved this month." />
        <TableWrap label={`${label} statement`}>
          <table className="data-table me-statement-table">
            <tbody>
              <tr className="is-subtotal"><th scope="row">Opening balance</th><td className="amount"><Money value={s.opening} /></td></tr>
              {ins.map((x) => <tr key={x.key}><th scope="row">{x.label}</th><td className="amount"><Money value={x.value} sign="exceptZero" /></td></tr>)}
              <tr className="is-subtotal"><th scope="row">Money in</th><td className="amount"><Money value={s.income} /></td></tr>
              {outs.map((x) => <tr key={x.key}><th scope="row">{x.label}</th><td className="amount"><Money value={x.value} /></td></tr>)}
              <tr className="is-subtotal"><th scope="row">Money out</th><td className="amount"><Money value={`-${s.spending}`} /></td></tr>
              {moves.map((x) => <tr key={x.key}><th scope="row">{x.label}</th><td className="amount"><Money value={x.value} sign="exceptZero" /></td></tr>)}
            <tr className="me-total"><th scope="row">{s.partial ? 'Balance so far' : 'Closing balance'}</th><td className="amount"><Money value={s.closing} /></td></tr>
            </tbody>
          </table>
        </TableWrap>
      </Section>

      <Section title="Tax paid" divider description={<Link href="/me/taxes" prefetch={false} className="me-no-print">All taxes and fines</Link>}>
        {Object.keys(s.taxByKind).length === 0 ? <p className="me-empty">No tax paid this month.</p> : <TableWrap label="Tax paid">
          <table className="data-table">
            <tbody>
              {Object.entries(s.taxByKind).map(([kind, v]) => <tr key={kind}><th scope="row">{taxKindLabel(kind)}</th><td className="amount"><Money value={v} /></td></tr>)}
            <tr className="me-total"><th scope="row">Total</th><td className="amount"><Money value={s.taxPaid} /></td></tr>
            </tbody>
          </table>
        </TableWrap>}
      </Section>
    </>}

    <Section title="Largest transactions" divider description={`The biggest movements in ${label}, in or out.`}>
      <FeedTable id="me-statement-largest" label={`Largest transactions, ${label}`} rows={s.largest} showAccount={s.personal.length > 1 && !s.account}
        empty={<p className="dt-empty-text">No transactions this month.</p>} />
    </Section>
  </div>;
}
