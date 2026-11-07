import { loginReturningTo } from '@/lib/actions/entity';

/** The AccessGate call to action: sign in and come back to this page. */
export function LoginButton({ returnTo, label = 'Log in to see your own account' }: { returnTo: string; label?: string }) {
  return <form action={loginReturningTo.bind(null, returnTo)}>
    <button type="submit" className="btn btn-primary">{label}</button>
  </form>;
}
