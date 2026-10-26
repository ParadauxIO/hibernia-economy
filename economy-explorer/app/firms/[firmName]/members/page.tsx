import Link from 'next/link';
import { AccessGate, Section } from '@democracycraft/design-system';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { Player } from '@/components/Player';
import { DateWithYear } from '@/components/entity/bits';
import { LoginButton } from '@/components/entity/LoginButton';
import { firmHref } from '@/lib/entityLinks';
import { buildMetadata } from '@/lib/metadata';
import { getFirmMembers, type FirmMembers } from '@/lib/services/firm';
import type { FirmEmployeeRow } from '@/lib/sql/firm';
import { FirmHeader, loadFirm } from '../shared';

export const dynamic = 'force-dynamic';

type Params = Promise<{ firmName: string }>;

export async function generateMetadata({ params }: { params: Params }) {
  const { firmName } = await params;
  const name = decodeURIComponent(firmName);
  return buildMetadata({ title: `${name} members`, description: `Staff and roles of ${name} on {server}.`, path: `/firms/${encodeURIComponent(name)}/members` });
}

/** Spec §7.3: the roster and roles, for the firm's members and staff with firm.read.all. */
export default async function FirmMembersPage({ params }: { params: Params }) {
  const { firmName } = await params;
  const loaded = await loadFirm(firmName, 'members');
  const members = loaded.canSeeMembers ? await getFirmMembers(loaded.firm.firm_id) : null;
  const path = `${firmHref(loaded.firm.display_name)}/members`;

  return <>
    <FirmHeader loaded={loaded} tab="members" />
    {members ? <Roster members={members} /> : <AccessGate kind={loaded.viewer.anon ? 'login' : 'request'} title="Members are private to the firm"
      description={<p>{loaded.firm.display_name} has {loaded.pub.summary.staff_count.toLocaleString('en-US')} staff. Who they are, and their roles, is visible to the firm itself.</p>}
      grants={['The firm’s proprietor or one of its employees', 'Staff with permission to read every firm']}
      action={loaded.viewer.anon ? <LoginButton returnTo={path} label="Log in" /> : <Link href="/me" className="btn">Your firms</Link>} />}
  </>;
}

function Roster({ members }: { members: FirmMembers }) {
  const staff: Column<FirmEmployeeRow>[] = [
    { key: 'player', header: 'Player', cell: (e) => <Player name={e.player_name} uuid={e.player_uuid} /> },
    { key: 'role', header: 'Role', cell: (e) => e.role_name },
    { key: 'joined', header: 'Joined', secondary: true, cell: (e) => <DateWithYear value={e.joined_at} /> },
  ];
  const roles: Column<FirmMembers['roles'][number]>[] = [
    { key: 'role', header: 'Role', cell: (r) => <>{r.name}{r.proprietor_like ? <span className="dt-sub"> · proprietor-level</span> : null}{r.default_role ? <span className="dt-sub"> · default</span> : null}</> },
    { key: 'members', header: 'Members', numeric: true, cell: (r) => r.members.toLocaleString('en-US') },
    { key: 'perms', header: 'Permissions', secondary: true, cell: (r) => r.permissions.length ? r.permissions.map((p) => p.charAt(0) + p.slice(1).toLowerCase()).join(', ') : <span className="muted">—</span> },
  ];
  return <>
    <Section title="Staff" description="Current employees, by role">
      <DataTable id="firm-staff" label="Staff" columns={staff} rows={members.employees} rowKey={(e) => `${e.player_uuid}-${e.role_name}`}
        empty={<p className="dt-empty-text">No current employees.</p>} />
    </Section>
    <Section title="Roles" divider>
      <DataTable id="firm-roles" label="Roles" columns={roles} rows={members.roles} rowKey={(r) => r.role_id} density="compact"
        empty={<p className="dt-empty-text">No roles.</p>} />
    </Section>
  </>;
}
