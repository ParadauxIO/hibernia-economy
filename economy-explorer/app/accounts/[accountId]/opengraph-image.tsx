import { OG_SIZE, ogCard } from '@/components/entity/OgCard';
import { findAccount } from '@/lib/auth/access';
import { playerName } from '@/lib/entityLinks';
import { accountLabel } from '@/lib/format';
import { brand } from '@/lib/metadata';
import { formatMoney } from '@/lib/money';
import { getAccountContext } from '@/lib/services/account';
import { resolveTheme } from '@/lib/theme';

export const size = OG_SIZE;
export const contentType = 'image/png';
export const alt = 'Account summary';

/** Name, type, holder and balance: everything here is on the public account page. */
export default async function Image({ params }: { params: Promise<{ accountId: string }> }) {
  const { accountId } = await params;
  const [id, theme] = [await brand(), await resolveTheme()];
  const account = /^[1-9]\d{0,9}$/.test(accountId) ? await findAccount(Number(accountId)) : null;
  if (!account) return ogCard({ theme, server: id.name, eyebrow: 'Account', title: 'Account not found' });
  const context = await getAccountContext(account);
  const type = account.account_type.charAt(0) + account.account_type.slice(1).toLowerCase();
  const holder = context.firm ? `Held by ${context.firm.display_name}`
    : account.account_type === 'PERSONAL' && account.owner_uuid ? `Personal account of ${playerName(account.owner_name, account.owner_uuid)}` : null;
  return ogCard({
    theme, server: id.name, eyebrow: `${type} account · #${account.account_id}`, title: accountLabel(account), subtitle: holder,
    figure: { label: 'Balance', value: formatMoney(account.balance) },
  });
}
