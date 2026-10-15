import 'server-only';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import matter from 'gray-matter';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import { remarkAlert } from 'remark-github-blockquote-alert';
import remarkRehype from 'remark-rehype';
import rehypeSlug from 'rehype-slug';
import rehypeAutolinkHeadings from 'rehype-autolink-headings';
import rehypeHighlight from 'rehype-highlight';
import rehypeStringify from 'rehype-stringify';
import { visit } from 'unist-util-visit';
import { rehypeLiveBlocks } from '@/lib/docsLive';

// ── Player documentation: build-time Markdown → static HTML ──────────────────
// Content lives in `docs/` at the repo root. Pages are statically generated
// (see app/docs/[[...slug]]/page.tsx), so every fs read here happens at build
// time — nothing in this module runs in the deployed standalone server.

const DOCS_DIR = path.join(process.cwd(), 'docs');

/** Folder whose pages are admin-only (gated at render; hidden from public nav). */
export const ADMIN_SECTION = 'admin';

/** True if a slug addresses the admin-only docs section. */
export function isAdminDoc(slug: string[]): boolean {
  return slug[0] === ADMIN_SECTION;
}

export interface DocHeading {
  depth: 2 | 3;
  id: string;
  text: string;
}

export interface DocPage {
  title: string;
  description: string | null;
  html: string;
  headings: DocHeading[];
}

export interface DocLink {
  title: string;
  href: string;
  order: number;
}

/** A sidebar group. `title === null` is the ungrouped top region (e.g. the
 *  Overview landing page); otherwise it's a folder section. */
export interface DocsNavGroup {
  title: string | null;
  href: string | null;
  order: number;
  items: DocLink[];
}

interface FrontMatter {
  title?: string;
  description?: string;
  order?: number;
}

// ── Path resolution + safety ─────────────────────────────────────────────────

/** Resolve a URL slug to an on-disk file, or null if it doesn't exist / escapes
 *  the docs dir. `/docs` (slug []) maps to docs/index.md; `/docs/x` tries
 *  docs/x.md then docs/x/index.md. */
async function resolveDocFile(slug: string[]): Promise<string | null> {
  if (slug.some((s) => !s || s.includes('..') || s.includes('/') || s.includes('\\'))) return null;
  const rel = slug.join('/');
  const candidates = slug.length === 0 ? ['index.md'] : [`${rel}.md`, `${rel}/index.md`];
  for (const c of candidates) {
    const abs = path.resolve(DOCS_DIR, c);
    if (abs !== DOCS_DIR && !abs.startsWith(DOCS_DIR + path.sep)) continue; // traversal guard
    try {
      const st = await fs.stat(abs);
      if (st.isFile()) return abs;
    } catch {
      /* not found — try next candidate */
    }
  }
  return null;
}

// ── Markdown rendering ───────────────────────────────────────────────────────

interface HastNodeish {
  value?: string;
  tagName?: string;
  properties?: { id?: string };
  children?: HastNodeish[];
}

/** Flatten a hast node's text content (used for TOC labels). */
function hastText(node: HastNodeish): string {
  if (typeof node.value === 'string') return node.value;
  if (!node.children) return '';
  return node.children.map(hastText).join('');
}

/** Collect h2/h3 headings (after rehype-slug, before autolink injects anchors). */
function collectHeadings(headings: DocHeading[]) {
  return (tree: unknown) => {
    visit(tree as never, 'element', (node: HastNodeish) => {
      if (node.tagName === 'h2' || node.tagName === 'h3') {
        const id = node.properties?.id;
        if (id) headings.push({ depth: node.tagName === 'h2' ? 2 : 3, id, text: hastText(node).trim() });
      }
    });
  };
}

/** Read + render one doc page. Returns null when the slug has no file. */
export async function getDocBySlug(slug: string[]): Promise<DocPage | null> {
  const file = await resolveDocFile(slug);
  if (!file) return null;
  const raw = await fs.readFile(file, 'utf8');
  const { content, data } = matter(raw);
  const fm = data as FrontMatter;
  const headings: DocHeading[] = [];

  const processed = await unified()
    .use(remarkParse)
    .use(remarkGfm)
    .use(remarkAlert) // GitHub-style > [!NOTE] / [!WARNING] callouts
    .use(remarkRehype)
    .use(rehypeLiveBlocks) // ```live fences → placeholders filled on the client
    .use(rehypeSlug)
    .use(() => collectHeadings(headings))
    .use(rehypeAutolinkHeadings, { behavior: 'append', properties: { className: ['doc-anchor'], ariaHidden: true, tabIndex: -1 }, content: { type: 'text', value: '#' } })
    .use(rehypeHighlight, { detect: false, ignoreMissing: true })
    .use(rehypeStringify)
    .process(content);

  return {
    title: fm.title ?? slug[slug.length - 1] ?? 'Documentation',
    description: fm.description ?? null,
    html: String(processed),
    headings,
  };
}

// ── Navigation tree ──────────────────────────────────────────────────────────

async function readFrontMatter(abs: string): Promise<FrontMatter> {
  try {
    return matter(await fs.readFile(abs, 'utf8')).data as FrontMatter;
  } catch {
    return {};
  }
}

function prettify(name: string): string {
  return name.replace(/[-_]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}

let _treeCache: DocsNavGroup[] | null = null;

/** Build the sidebar tree from the docs/ folder structure. One level of
 *  folders (sections) + their pages; root-level pages form the top group.
 *  Order/labels come from each file's frontmatter (folder `index.md` names its
 *  section). Memoized — content is fixed at build time. */
export async function getDocsTree(): Promise<DocsNavGroup[]> {
  if (_treeCache) return _treeCache;
  const entries = await fs.readdir(DOCS_DIR, { withFileTypes: true });
  const topItems: DocLink[] = [];
  const sections: DocsNavGroup[] = [];

  for (const entry of entries) {
    if (entry.isFile() && entry.name.endsWith('.md')) {
      const slug = entry.name === 'index.md' ? '' : entry.name.slice(0, -3);
      const fm = await readFrontMatter(path.join(DOCS_DIR, entry.name));
      topItems.push({
        title: fm.title ?? (slug === '' ? 'Overview' : prettify(slug)),
        href: slug === '' ? '/docs' : `/docs/${slug}`,
        order: fm.order ?? (slug === '' ? -1 : 0),
      });
    } else if (entry.isDirectory()) {
      // The admin section is role-gated and kept out of the public tree
      // (sidebar/sitemap). It's surfaced to admins separately (DocsSidebar) and
      // its pages gate on the viewer at render time.
      if (entry.name === ADMIN_SECTION) continue;
      const dir = path.join(DOCS_DIR, entry.name);
      const indexFm = await readFrontMatter(path.join(dir, 'index.md'));
      const files = (await fs.readdir(dir)).filter((f) => f.endsWith('.md'));
      const items: DocLink[] = [];
      for (const f of files) {
        if (f === 'index.md') continue;
        const slug = f.slice(0, -3);
        const fm = await readFrontMatter(path.join(dir, f));
        items.push({ title: fm.title ?? prettify(slug), href: `/docs/${entry.name}/${slug}`, order: fm.order ?? 0 });
      }
      items.sort((a, b) => a.order - b.order || a.title.localeCompare(b.title));
      const hasIndex = files.includes('index.md');
      sections.push({
        title: indexFm.title ?? prettify(entry.name),
        href: hasIndex ? `/docs/${entry.name}` : null,
        order: indexFm.order ?? 100,
        items,
      });
    }
  }

  topItems.sort((a, b) => a.order - b.order || a.title.localeCompare(b.title));
  sections.sort((a, b) => a.order - b.order || (a.title ?? '').localeCompare(b.title ?? ''));
  const groups: DocsNavGroup[] = [];
  if (topItems.length) groups.push({ title: null, href: null, order: -1, items: topItems });
  groups.push(...sections);
  _treeCache = groups;
  return groups;
}

/** Every renderable doc slug, for generateStaticParams. Includes [] for /docs
 *  and [folder] for each folder index.md. */
export async function getAllDocSlugs(): Promise<string[][]> {
  const slugs: string[][] = [];
  const entries = await fs.readdir(DOCS_DIR, { withFileTypes: true });
  for (const entry of entries) {
    if (entry.isFile() && entry.name.endsWith('.md')) {
      slugs.push(entry.name === 'index.md' ? [] : [entry.name.slice(0, -3)]);
    } else if (entry.isDirectory()) {
      const files = await fs.readdir(path.join(DOCS_DIR, entry.name));
      for (const f of files) {
        if (!f.endsWith('.md')) continue;
        slugs.push(f === 'index.md' ? [entry.name] : [entry.name, f.slice(0, -3)]);
      }
    }
  }
  return slugs;
}
