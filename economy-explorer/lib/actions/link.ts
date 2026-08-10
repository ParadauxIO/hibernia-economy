'use server';
import { randomInt } from 'node:crypto';
import { revalidatePath } from 'next/cache';
import { getViewer } from '@/lib/auth/viewer';
import { insertLinkCode, deleteExpiredLinkCodes } from '@/lib/sql/link';

const ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const CODE_LEN = 8;
const TTL_SECONDS = 300;

export interface LinkStartResult {
  ok: boolean;
  code?: string;
  alreadyLinked?: boolean;
  message?: string;
  error?: string;
}

/** Mirrors LinkController.start (74 LOC Spring controller). */
export async function linkStart(): Promise<LinkStartResult> {
  const viewer = await getViewer();
  if (viewer.anon) return { ok: false, error: 'You must be signed in to generate a link code.' };
  if (viewer.linked) return { ok: true, alreadyLinked: true, message: 'This account is already linked.' };

  await deleteExpiredLinkCodes();

  for (let attempt = 0; attempt < 5; attempt++) {
    const code = generateCode();
    try {
      await insertLinkCode({
        code,
        sub: viewer.keycloakSub,
        minecraftName: viewer.minecraftName,
        ttlSeconds: TTL_SECONDS,
      });
      revalidatePath('/link');
      return {
        ok: true,
        code,
        message: `Run /treasuryapi ui link ${code} in-game within 5 minutes.`,
      };
    } catch (e) {
      // Astronomically unlikely PK collision — try again.
      if (attempt === 4) {
        return { ok: false, error: e instanceof Error ? e.message : String(e) };
      }
    }
  }
  return { ok: false, error: 'Failed to generate a code.' };
}

function generateCode(): string {
  // CSPRNG: these codes bind a Keycloak identity to a Minecraft UUID, so they're
  // an auth-adjacent secret — Math.random() is not cryptographically secure.
  let out = '';
  for (let i = 0; i < CODE_LEN; i++) {
    out += ALPHABET[randomInt(ALPHABET.length)];
  }
  return out;
}
