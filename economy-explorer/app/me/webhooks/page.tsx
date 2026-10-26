import { permanentRedirect } from 'next/navigation';

/** Webhooks moved to the Developer section of /me (spec §8.7). */
export default function OldWebhooksPage(): never {
  permanentRedirect('/me/developer/webhooks');
}
