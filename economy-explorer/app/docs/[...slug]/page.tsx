import { hasPermission } from '@/lib/auth/policy';
import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { getAllDocSlugs, getDocBySlug, isAdminDoc } from '@/lib/docs';
import { getViewer } from '@/lib/auth/viewer';
import { PrivacyGate } from '@/components/PrivacyGate';
import { DocTopNote } from '@/components/docs/DocTopNote';
import { DocsToc } from '@/components/docs/DocsToc';

// Statically generated from the docs/ tree at build time; unknown slugs 404
// instead of hitting the filesystem at runtime (keeps the standalone server
// free of any docs/ dependency).
export const dynamicParams = false;

export async function generateStaticParams() {
  return (await getAllDocSlugs()).filter((slug) => slug.length > 0).map((slug) => ({ slug }));
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug?: string[] }>;
}): Promise<Metadata> {
  const { slug } = await params;
  const doc = await getDocBySlug(slug ?? []);
  if (!doc) return {};
  // Admin docs are gated and must never be indexed.
  if (isAdminDoc(slug ?? [])) {
    return { title: { absolute: `${doc.title} · Economy Explorer Docs` }, robots: { index: false, follow: false } };
  }
  const title = `${doc.title} · Economy Explorer Docs`;
  const description = doc.description ?? undefined;
  // Docs are statically generated and shared across both server deployments,
  // so — unlike the rest of the explorer — they are intentionally server-neutral
  // (no cookie/env-derived branding, which can't be read at build time).
  return {
    title: { absolute: title },
    description,
    openGraph: { title, description, type: 'article' },
    twitter: { card: 'summary', title, description },
    // Docs are public — override the site-wide noindex (also stripped at the
    // HTTP layer in next.config.ts).
    robots: { index: true, follow: true },
  };
}

export default async function DocPage({
  params,
}: {
  params: Promise<{ slug?: string[] }>;
}) {
  const { slug } = await params;
  const safeSlug = slug ?? [];
  const doc = await getDocBySlug(safeSlug);
  if (!doc) notFound();

  // Admin section: gate on the viewer's role at request time (reading the viewer
  // makes only these pages dynamic; the rest of /docs stays static). Non-admins
  // get a gate instead of the content.
  if (isAdminDoc(safeSlug)) {
    const viewer = await getViewer();
    if (!hasPermission(viewer, 'docs.admin.read')) {
      return (
        <>
          <PrivacyGate
            kind={viewer.anon ? 'login' : 'private'}
            title="Admin documentation"
            hint={viewer.anon ? 'Sign in with an admin account to view this section.' : 'This section is only available to Explorer admins.'}
          />
          <DocsToc headings={[]} />
        </>
      );
    }
  }

  return (
    <>
      <article className="doc-prose">
        {!isAdminDoc(safeSlug) && <DocTopNote path={safeSlug.length ? `/docs/${safeSlug.join('/')}` : '/docs'} />}
        <div dangerouslySetInnerHTML={{ __html: doc.html }} />
      </article>
      <DocsToc headings={doc.headings} />
    </>
  );
}
