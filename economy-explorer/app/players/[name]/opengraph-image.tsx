import { OG_SIZE, ogCard } from '@/components/entity/OgCard';
import { playerName } from '@/lib/entityLinks';
import { brand } from '@/lib/metadata';
import { formatMoney } from '@/lib/money';
import { getPlayerProfile, resolvePlayer } from '@/lib/services/players';
import { resolveTheme } from '@/lib/theme';

export const size = OG_SIZE;
export const contentType = 'image/png';
export const alt = 'Player profile';

/** Name, firm roles and their personal balance (public on the profile). Never a firm's money. */
export default async function Image({ params }: { params: Promise<{ name: string }> }) {
  const { name } = await params;
  const [id, theme] = [await brand(), await resolveTheme()];
  const resolved = await resolvePlayer(decodeURIComponent(name));
  if (!resolved) return ogCard({ theme, server: id.name, eyebrow: 'Player', title: 'Player not found' });
  const profile = await getPlayerProfile(resolved.uuid);
  const roles = profile.firms.slice(0, 2).map((f) => (f.proprietor ? `Proprietor of ${f.firm_name}` : `${f.role_name ?? 'Employee'} at ${f.firm_name}`));
  const personal = profile.accounts.find((a) => !a.archived);
  return ogCard({
    theme, server: id.name, eyebrow: 'Player', title: playerName(resolved.player?.name, resolved.uuid),
    subtitle: roles.length ? roles.join(' · ') : null,
    figure: personal ? { label: 'Personal account', value: formatMoney(personal.balance) } : null,
  });
}
