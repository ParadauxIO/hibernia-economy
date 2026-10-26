// The explorer's information architecture (spec §6.1), as data. Pure and
// isomorphic: the top bar, the section nav, the mobile menu, the command
// palette and Breadcrumbs all read this one model, and the chrome infers the
// active section from the path, so pages never declare it.
//
// Visibility here is presentation only. Every page still gates server-side.
import type { Permission } from '@/lib/auth/policy';

/** What the nav needs to know about the viewer. A subset of ViewerCtx. */
export interface NavViewer {
  permissions: readonly Permission[];
  /** Server-computed: government.read.all, or government.read with a GOVERNMENT account in scope (§10.1). */
  canSeeGovernment: boolean;
}

export interface NavItem {
  label: string;
  href: string;
  /** Inclusive path match. Default: exact match on href. */
  match?: (path: string) => boolean;
  visible?: (viewer: NavViewer) => boolean;
}

export type SectionId = 'economy' | 'directory' | 'market' | 'government' | 'investigate' | 'docs';

export interface NavSection {
  id: SectionId;
  label: string;
  /** Where the top-bar entry goes. */
  href: string;
  match: (path: string) => boolean;
  visible?: (viewer: NavViewer) => boolean;
  /** Section pages shown in the secondary nav under the top bar. Omitted when the section is one page or has its own navigation. */
  items?: NavItem[];
}

const under = (prefix: string) => (p: string) => p === prefix || p.startsWith(`${prefix}/`);
const has = (permission: Permission) => (v: NavViewer) => v.permissions.includes(permission);

/** /gov is built by the GOV workstream; until it lands the section links to the current page. */
export const GOVERNMENT_HREF = '/government';

export const NAV_SECTIONS: readonly NavSection[] = [
  {
    id: 'economy',
    label: 'Economy',
    href: '/',
    match: (p) => p === '/' || under('/economy')(p) || under('/money-flow')(p),
    items: [
      { label: 'Overview', href: '/' },
      { label: 'Wealth', href: '/economy/wealth', match: under('/economy/wealth') },
      { label: 'Money flow', href: '/money-flow', match: under('/money-flow') },
      { label: 'Taxation', href: '/economy/taxation', match: under('/economy/taxation') },
    ],
  },
  {
    id: 'directory',
    label: 'Directory',
    href: '/accounts',
    match: (p) => under('/players')(p) || under('/accounts')(p) || under('/firms')(p),
    items: [
      { label: 'Players', href: '/players', match: under('/players') },
      { label: 'Accounts', href: '/accounts', match: under('/accounts') },
      { label: 'Firms', href: '/firms', match: under('/firms') },
    ],
  },
  {
    id: 'market',
    label: 'Market',
    href: '/market',
    match: (p) => under('/market')(p) || under('/chestshop')(p),
    items: [
      { label: 'Items', href: '/market', match: (p) => p === '/market' || p === '/chestshop' || under('/chestshop/items')(p) },
      { label: 'Find shops', href: '/chestshop/shops', match: under('/chestshop/shops') },
      { label: 'Activity', href: '/market/activity', match: under('/market/activity') },
      { label: 'Market firms', href: '/chestshop/firms', match: under('/chestshop/firms') },
      { label: 'Sales', href: '/chestshop/sales', match: under('/chestshop/sales'), visible: has('market.sales.read') },
    ],
  },
  {
    id: 'government',
    label: 'Government',
    href: GOVERNMENT_HREF,
    match: (p) => under('/government')(p) || under('/gov')(p),
    visible: (v) => v.canSeeGovernment,
  },
  {
    id: 'investigate',
    label: 'Investigate',
    href: '/transactions',
    match: under('/transactions'),
    visible: has('transactions.read'),
  },
  { id: 'docs', label: 'Docs', href: '/docs', match: under('/docs') },
];

function isVisible(entry: { visible?: (v: NavViewer) => boolean }, viewer: NavViewer): boolean {
  return entry.visible ? entry.visible(viewer) : true;
}

/** Top-bar sections this viewer may see, in order. */
export function visibleSections(viewer: NavViewer): NavSection[] {
  return NAV_SECTIONS.filter((s) => isVisible(s, viewer));
}

/** The section that owns `path`, whether or not the viewer can see it. */
export function sectionForPath(path: string): NavSection | null {
  return NAV_SECTIONS.find((s) => s.match(path)) ?? null;
}

export function itemMatches(item: NavItem, path: string): boolean {
  return item.match ? item.match(path) : path === item.href;
}

/** The secondary-nav items for `path`'s section, or null when it has none. */
export function sectionItems(path: string, viewer: NavViewer): { section: NavSection; items: NavItem[] } | null {
  const section = sectionForPath(path);
  if (!section?.items || !isVisible(section, viewer)) return null;
  return { section, items: section.items.filter((i) => isVisible(i, viewer)) };
}

export interface Crumb {
  label: string;
  href?: string | undefined;
}

/**
 * The leading breadcrumbs for a section page, e.g. `/firms` →
 * Directory › Firms. Page agents append the entity: `[...sectionCrumbs('/firms'), { label: firm.name }]`.
 * Unknown paths return [].
 */
export function sectionCrumbs(path: string): Crumb[] {
  const section = sectionForPath(path);
  if (!section) return [];
  const crumbs: Crumb[] = [{ label: section.label, href: section.href }];
  const item = section.items?.find((i) => itemMatches(i, path));
  if (item) crumbs.push({ label: item.label, href: item.href });
  return crumbs;
}

/** The account-menu destinations (§6.1). Shared with the command palette. */
export interface AccountLink {
  label: string;
  href: string;
}

export const ACCOUNT_LINKS: readonly AccountLink[] = [
  { label: 'My finances', href: '/me' },
  { label: 'My transactions', href: '/me/transactions' },
  { label: 'Statements', href: '/me/statements' },
  { label: 'My market', href: '/me/market' },
  { label: 'My firms', href: '/me/firms' },
  { label: 'Webhooks and API', href: '/me/developer' },
  { label: 'Link status', href: '/link' },
];
