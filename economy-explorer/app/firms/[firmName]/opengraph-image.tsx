import { OG_SIZE, ogCard } from '@/components/entity/OgCard';
import { findFirmByDisplayName } from '@/lib/auth/access';
import { playerName } from '@/lib/entityLinks';
import { brand } from '@/lib/metadata';
import { formatMoney, sumBy } from '@/lib/money';
import { getFirmPublic } from '@/lib/services/firm';
import { resolveTheme } from '@/lib/theme';

export const size = OG_SIZE;
export const contentType = 'image/png';
export const alt = 'Firm summary';

/** Name, proprietor (as a role), size and the firm's own balance, all public on the firm page. */
export default async function Image({ params }: { params: Promise<{ firmName: string }> }) {
  const { firmName } = await params;
  const [id, theme] = [await brand(), await resolveTheme()];
  const firm = await findFirmByDisplayName(decodeURIComponent(firmName));
  const pub = firm ? await getFirmPublic(firm.firm_id) : null;
  if (!firm || !pub) return ogCard({ theme, server: id.name, eyebrow: 'Firm', title: 'Firm not found' });
  const s = pub.summary;
  return ogCard({
    theme, server: id.name, eyebrow: `Firm · ${s.staff_count.toLocaleString('en-US')} staff · ${s.account_count.toLocaleString('en-US')} accounts`,
    title: firm.display_name,
    subtitle: s.proprietor_uuid ? `Proprietor: ${playerName(s.proprietor_name, s.proprietor_uuid)}` : null,
    figure: { label: 'Firm balance', value: formatMoney(sumBy(pub.accounts.filter((a) => !a.archived), (a) => a.balance)) },
  });
}
