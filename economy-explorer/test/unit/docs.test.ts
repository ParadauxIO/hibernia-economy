import { describe, expect, it } from 'vitest';
import { readFile } from 'node:fs/promises';
import { getAllDocSlugs, getDocBySlug, getDocsTree, isAdminDoc, type DocPage } from '@/lib/docs';

describe('published documentation', () => {
  it('renders every page and resolves internal documentation links and anchors', async () => {
    const slugs = await getAllDocSlugs();
    const pages = new Map<string, DocPage>(await Promise.all(slugs.map(async (slug) => {
      const doc = await getDocBySlug(slug);
      expect(doc, `missing page ${slug.join('/')}`).not.toBeNull();
      expect(doc?.title).toBeTruthy();
      expect(doc?.description, `missing description ${slug.join('/')}`).toBeTruthy();
      expect(doc?.html).toContain('<h1');
      return [`/docs${slug.length ? `/${slug.join('/')}` : ''}`, doc!] as const;
    })));
    const failures: string[] = [];
    for (const [source, page] of pages) {
      for (const match of page.html.matchAll(/href="([^\"]+)"/g)) {
        const href = match[1]!;
        if (!href.startsWith('/docs') && !href.startsWith('#')) continue;
        const url = new URL(href, `https://docs.example${source}`);
        const target = pages.get(url.pathname);
        if (!target) failures.push(`${source} → ${href}: missing page`);
        else if (url.hash && !target.html.includes(`id="${decodeURIComponent(url.hash.slice(1))}"`)) {
          failures.push(`${source} → ${href}: missing anchor`);
        }
      }
    }
    expect(failures).toEqual([]);
  });

  it('keeps admin pages out of public navigation and exposes valid sidebar targets', async () => {
    const tree = await getDocsTree();
    const links = tree.flatMap(group => [...(group.href ? [group.href] : []), ...group.items.map(item => item.href)]);
    expect(links.some(href => href.startsWith('/docs/admin'))).toBe(false);
    const slugs = await getAllDocSlugs();
    expect(slugs.some(slug => slug.join('/') === 'admin/operator-runbook')).toBe(true);
    const publicSlugs = slugs.filter(slug => !isAdminDoc(slug));
    for (const slug of publicSlugs) {
      expect(links).toContain(`/docs${slug.length ? `/${slug.join('/')}` : ''}`);
    }
    // The admin sidebar is intentionally separate from the public tree.
    const sidebar = await readFile('components/docs/DocsSidebar.tsx', 'utf8');
    for (const [, href] of sidebar.matchAll(/href: '(\/docs\/admin[^']*)'/g)) {
      expect(await getDocBySlug(href!.slice('/docs/'.length).split('/')), href).not.toBeNull();
    }
  });
});
