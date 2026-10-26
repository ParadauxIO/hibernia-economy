import { EntityLink } from '@democracycraft/design-system/next';
import type { Party } from '@/lib/services/meFlows';

/**
 * An account as a link, named the way a player thinks of it: a firm's account
 * by its firm ("Acme Corp · Payroll"), a personal account by its owner.
 */
export function PartyLink({ party }: { party: Pick<Party, 'accountId' | 'accountType' | 'name' | 'firmName'> }) {
  const name = party.firmName && !party.name.startsWith(party.firmName) ? `${party.firmName} · ${party.name}` : party.name;
  return <EntityLink kind="account" href={`/accounts/${party.accountId}`} name={name} accountType={party.accountType} id={party.accountId} />;
}
