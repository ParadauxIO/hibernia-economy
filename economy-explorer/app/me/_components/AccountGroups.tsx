import Link from 'next/link';
import type { Route } from 'next';
import { Money, TableWrap } from '@democracycraft/design-system';
import { EntityLink } from '@democracycraft/design-system/next';
import { relationLabel, type AccountGroup } from '@/lib/services/meGroups';

/**
 * Accounts the player can access, grouped by whose money they are (spec §8.1).
 * Each group has its own subtotal, labelled with whose money it is; groups are
 * never added together, and only "Mine" is the player's money.
 */
export function AccountGroups({ groups, firmLinks = true }: { groups: readonly AccountGroup[]; firmLinks?: boolean }) {
  return <div className="me-groups">
    {groups.map((g) => {
      const headingId = `me-group-${g.key}`;
      return <section key={g.key} className="me-group" data-kind={g.kind} aria-labelledby={headingId}>
        <header className="me-group-head">
          <div>
            <h3 id={headingId} className="me-group-title">
              {g.kind === 'business' && g.firmId !== null && firmLinks
                ? <Link href={`/firms/${encodeURIComponent(g.title)}` as Route} prefetch={false}>{g.title}</Link>
                : g.title}
              {g.role && <span className="me-group-role"> · your role: {g.role}</span>}
            </h3>
            <p className="me-group-sub">{g.subtitle}</p>
          </div>
          {g.subtotal !== null && <div className="me-group-total">
            <span className="me-group-total-label">{g.subtotalLabel}</span>
            <Money value={g.subtotal} />
          </div>}
        </header>
        {g.note && <p className="me-note" role="note">{g.note}</p>}
        {g.accounts.length === 0
          ? <p className="me-empty">{g.kind === 'mine' ? 'You have no personal account yet. One is opened the first time you join the server.' : 'None.'}</p>
          : <TableWrap label={`${g.title} accounts`}>
            <table className="data-table me-accounts-table">
              <thead><tr>
                <th scope="col">Account</th>
                <th scope="col">Your access</th>
                <th scope="col" className="amount">Balance</th>
              </tr></thead>
              <tbody>{g.accounts.map((a) => <tr key={a.id}>
                <td>
                  <EntityLink kind="account" href={`/accounts/${a.id}`} name={a.name} accountType={a.type} id={a.id} />
                  {a.archived && <span className="dt-sub"> · closed</span>}
                </td>
                <td><span className="me-relations">{a.relations.map((r) => <span key={`${r.kind}:${r.detail ?? ''}`} className="me-relation" data-kind={r.kind}>{relationLabel(r, a.type)}</span>)}</span></td>
                <td className="amount"><Money value={a.balance} /></td>
              </tr>)}</tbody>
            </table>
          </TableWrap>}
      </section>;
    })}
  </div>;
}
