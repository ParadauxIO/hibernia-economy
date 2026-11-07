import { PageHeader, Section, Tag } from '@democracycraft/design-system';
import { getViewer } from '@/lib/auth/viewer';
import { hasPermission } from '@/lib/auth/policy';
import { getFeatureFlagsForAdmin } from '@/lib/services/featureFlags';
import { AdminGate } from '../_components/AdminGate';
import { FlagToggle } from './toggle';

export const dynamic = 'force-dynamic';
export const metadata = { title: 'Feature flags' };

export default async function FeatureFlagsPage() {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'feature_flags.read')) return <AdminGate viewer={viewer} title="Feature flag access required" permission="feature_flags.read" />;
  const flags = await getFeatureFlagsForAdmin();
  const canUpdate = hasPermission(viewer, 'feature_flags.update');
  return <>
    <PageHeader title="Feature flags" subtitle="Control release features across this economy's game servers, API and Explorer." />
    <p>Changes normally apply within five seconds plus query time. Disabling stops new work; operations already underway finish normally.</p>
    <p>These are saved database values. A service without a fresh database connection disables features after 30 seconds.</p>
    {flags.length === 0 && <p>No feature flags registered. New features register disabled flags through a database migration.</p>}
    {flags.map(flag => <Section key={flag.name} title={flag.name} description={flag.description} divider>
      <Tag tone={flag.enabled === 1 ? 'accent' : 'neutral'}>{flag.enabled === 1 ? 'Enabled' : 'Disabled'}</Tag>
      <p className="muted">Last changed: {flag.updated_at.toISOString()} · {flag.updated_by ?? 'Database migration'} · Revision {flag.revision}</p>
      {canUpdate && <FlagToggle name={flag.name} enabled={flag.enabled === 1} revision={flag.revision} />}
    </Section>)}
  </>;
}
