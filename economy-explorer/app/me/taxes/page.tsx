import Link from 'next/link';
import type { Route } from 'next';
import { MetricCard, MetricRow, Money, PageHeader, Section, TableWrap, Tag, When } from '@democracycraft/design-system';
import { EntityLink } from '@democracycraft/design-system/next';
import { getViewer } from '@/lib/auth/viewer';
import { buildMetadata } from '@/lib/metadata';
import { meGate } from '@/lib/services/me';
import { monthLabel, taxKindLabel } from '@/lib/services/meFlows';
import { FINES_LIMIT, getMyTaxes } from '@/lib/services/meTaxes';
import { PartlyCovered, Preparing, Updated } from '../_components/Freshness';
import { MeGateView } from '../_components/MeGateView';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Taxes and fines', description: 'Tax you paid on {server}, fines you received and the rates that apply to you.', path: '/me/taxes' });
}

export default async function MyTaxesPage() {
  const viewer = await getViewer();
  const gate = meGate(viewer);
  if (!gate.ok) return <MeGateView kind={gate.kind} title="Taxes and fines" />;
  const t = await getMyTaxes(viewer, gate.uuid);
  const covered = t.coverage.coveredTo !== null;
  const { balanceTax, incomeTax } = t.rates;

  return <>
    <PageHeader title="Taxes and fines" subtitle="Tax paid from your personal accounts, fines issued to you, and the rates in force. Taxes your firms pay are the firm’s, on the firm’s pages." />
    <MetricRow label="Taxes and fines summary">
      <MetricCard label="Tax paid, last 12 months" value={covered ? <Money value={t.total} /> : '—'}
        {...(covered && !t.coverage.covered && t.coverage.coveredFrom ? { meta: `Since ${t.coverage.coveredFrom}, as far back as history is prepared` } : {})} />
      <MetricCard label="Tax paid, this month" value={covered ? <Money value={t.byMonth[0]!.total} /> : '—'} />
      <MetricCard label="Fines in force" value={<Money value={t.activeFinesTotal} />} meta={`${t.fines.filter((f) => f.revoked === 0).length} active`} />
      <MetricCard label="Fines, all time" value={<Money value={t.finesTotal} />} meta={`${t.fines.length} issued`} />
    </MetricRow>

    <Section title="Tax paid by month" description={<>By kind of tax, from your personal accounts. <Updated coverage={t.coverage} /></>}>
      {!covered ? <Preparing coverage={t.coverage} what="the last 12 months" /> : <>
        <PartlyCovered coverage={t.coverage} />
        <TableWrap label="Tax paid by month">
          <table className="data-table">
            <thead><tr>
              <th scope="col">Month</th>
              {t.kinds.map((k) => <th key={k} scope="col" className="amount">{taxKindLabel(k)}</th>)}
              <th scope="col" className="amount">Total</th>
            </tr></thead>
            <tbody>{t.byMonth.map((m) => <tr key={m.month}>
              <th scope="row"><Link href={`/me/statements/${m.month}` as Route} prefetch={false}>{monthLabel(m.month)}</Link></th>
              {m.covered ? <>
                {t.kinds.map((k) => <td key={k} className="amount"><Money value={m.byKind[k] ?? '0.00'} /></td>)}
                <td className="amount"><Money value={m.total} /></td>
              </> : <td colSpan={t.kinds.length + 1} className="amount muted">Preparing history</td>}
            </tr>)}</tbody>
          </table>
        </TableWrap>
      </>}
    </Section>

    <Section title="Fines received" divider description={t.fines.length >= FINES_LIMIT ? `Your latest ${FINES_LIMIT} fines.` : 'Every fine issued to you, newest first.'}>
      {t.fines.length === 0 ? <p className="me-empty">You have never been fined.</p> : <TableWrap label="Fines received">
        <table className="data-table">
          <thead><tr>
            <th scope="col">Reason</th>
            <th scope="col">Issued by</th>
            <th scope="col">Date</th>
            <th scope="col">Status</th>
            <th scope="col">Transaction</th>
            <th scope="col" className="amount">Amount</th>
          </tr></thead>
          <tbody>{t.fines.map((f) => <tr key={f.fine_id}>
            <td>{f.reason}</td>
            <td>
              <EntityLink kind="account" href={`/accounts/${f.gov_account_id}`} name={f.gov_account_name ?? `Account #${f.gov_account_id}`} accountType="GOVERNMENT" id={f.gov_account_id} />
              {f.issued_by_name && <span className="dt-sub"> · by {f.issued_by_name}</span>}
            </td>
            <td><When value={f.issued_at} /></td>
            <td>{f.revoked ? <Tag tone="neutral">Revoked{f.revoked_at ? <> <When value={f.revoked_at} /></> : null}</Tag> : <Tag tone="warn">Active</Tag>}</td>
            <td>
              <Link href={`/transactions/${f.txn_id}` as Route} prefetch={false}>#{f.txn_id}</Link>
              {f.revoke_txn_id && <> · refund <Link href={`/transactions/${f.revoke_txn_id}` as Route} prefetch={false}>#{f.revoke_txn_id}</Link></>}
            </td>
            <td className="amount"><Money value={f.amount} /></td>
          </tr>)}</tbody>
        </table>
      </TableWrap>}
    </Section>

    <Section title="Rates that apply to you" divider description={<>As the economy publishes them. <Link href="/docs/guides/tax" prefetch={false}>How tax works</Link></>}>
      <div className="me-split">
        <div>
          <h3 className="me-subhead">Balance tax {balanceTax && !balanceTax.enabled && <Tag tone="neutral">Off</Tag>}</h3>
          {!balanceTax ? <p className="me-empty">Not published.</p> : <>
            <p className="me-note">Charged on your personal balance, now <Money value={t.personalBalance} />. The whole balance pays the rate of its band.</p>
            <TableWrap label="Balance tax bands">
              <table className="data-table">
                <thead><tr><th scope="col">Balance</th><th scope="col" className="amount">Weekly rate</th></tr></thead>
                <tbody>{balanceTax.brackets.map((b) => <tr key={b.band}><td>{b.band}</td><td className="amount">{b.rate}</td></tr>)}</tbody>
              </table>
            </TableWrap>
          </>}
        </div>
        <div>
          <h3 className="me-subhead">Income tax {incomeTax && !incomeTax.enabled && <Tag tone="neutral">Off</Tag>}</h3>
          {!incomeTax ? <p className="me-empty">Not published.</p> : <>
            <p className="me-note">Taken from income paid to you by these sources; {incomeTax.defaultRate} elsewhere.</p>
            <TableWrap label="Income tax rates">
              <table className="data-table">
                <thead><tr><th scope="col">Source</th><th scope="col" className="amount">Rate</th></tr></thead>
                <tbody>{incomeTax.sources.map((s) => <tr key={s.plugin}><td>{s.plugin}</td><td className="amount">{s.rate}</td></tr>)}</tbody>
              </table>
            </TableWrap>
          </>}
        </div>
      </div>
    </Section>
  </>;
}
