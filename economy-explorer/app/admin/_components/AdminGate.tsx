import { AccessGate } from '@democracycraft/design-system';
import { signIn } from '@/lib/auth/authjs';
import type { Viewer } from '@/lib/auth/viewer';

/**
 * Shown in place of an admin page the viewer can't open: a sign-in prompt for
 * visitors, or the permission it needs for signed-in players.
 */
export function AdminGate({ viewer, title, permission }: { viewer: Viewer; title: string; permission?: string }) {
  if (viewer.anon) {
    return (
      <AccessGate
        kind="login"
        title={title}
        description="Sign in to use the admin tools your roles allow."
        action={
          <form action={async () => { 'use server'; await signIn('keycloak'); }}>
            <button type="submit" className="btn btn-primary">Log in</button>
          </form>
        }
      />
    );
  }
  return (
    <AccessGate
      kind="private"
      title={title}
      description={permission
        ? <>This tool needs the <code>{permission}</code> permission. An administrator can grant it through a role in Admin → Roles.</>
        : 'None of your roles include an admin permission.'}
    />
  );
}
