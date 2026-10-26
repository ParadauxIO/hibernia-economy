import 'server-only';
import type { Metadata } from 'next';
import { buildMetadata, type PageMeta } from '@/lib/metadata';

/**
 * buildMetadata, with the entity's generated card (the route's
 * opengraph-image) as the unfurl image instead of the server emblem. The
 * config value would otherwise win over the file convention.
 */
export async function entityMetadata(meta: PageMeta & { path: string }, alt: string): Promise<Metadata> {
  const base = await buildMetadata(meta);
  const image = { url: `${meta.path}/opengraph-image`, width: 1200, height: 630, alt };
  return {
    ...base,
    openGraph: { ...base.openGraph, images: [image] },
    twitter: { ...base.twitter, card: 'summary_large_image', images: [image.url] },
  };
}
