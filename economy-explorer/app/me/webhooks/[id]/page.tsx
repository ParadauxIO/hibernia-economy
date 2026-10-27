import { notFound, permanentRedirect } from 'next/navigation';

/** Webhooks moved to the Developer section of /me (spec §8.7). */
export default async function OldWebhookPage({ params }: { params: Promise<{ id: string }> }): Promise<never> {
  const { id } = await params;
  if (!/^\d{1,10}$/.test(id)) notFound();
  permanentRedirect(`/me/developer/webhooks/${id}`);
}
