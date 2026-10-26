'use server';
import { redirect } from 'next/navigation';
import { signIn, signOut } from '@/lib/auth/authjs';
import { federatedLogoutUrl } from '@/lib/auth/endSession';

// Server actions for the header's account controls. Single IdP: straight to Keycloak.
export async function loginAction() {
  await signIn('keycloak', { redirectTo: '/' });
}

/**
 * Clear the Auth.js session cookie, then end the Keycloak SSO session too
 * (lib/auth/endSession). Without the second step the next "Log in" signs the
 * user straight back in without asking. Falls back to a local logout when the
 * issuer or site URL isn't configured (local dev).
 */
export async function logoutAction() {
  await signOut({ redirect: false });
  redirect((await federatedLogoutUrl()) ?? '/');
}
