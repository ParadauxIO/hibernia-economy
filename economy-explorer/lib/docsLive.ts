import { visit } from 'unist-util-visit';

// Live blocks in the docs (PAR-80). The docs are static and shared by every
// tenant, so a value that differs per server or changes with config (tax rates)
// can't be written into the Markdown. Instead a page marks where it goes:
//
//   ```live
//   balance-tax
//   ```
//
// At build time rehypeLiveBlocks swaps that fence for an empty placeholder, and
// the LiveDocBlocks client component fills it from the running server.

export const LIVE_BLOCKS = ['balance-tax', 'income-tax'] as const;
export type LiveBlock = (typeof LIVE_BLOCKS)[number];

export function isLiveBlock(name: string | undefined): name is LiveBlock {
  return (LIVE_BLOCKS as readonly string[]).includes(name ?? '');
}

interface HastNodeish {
  type: string;
  value?: string;
  tagName?: string;
  properties?: Record<string, unknown>;
  children?: HastNodeish[];
}

function text(node: HastNodeish): string {
  return typeof node.value === 'string' ? node.value : (node.children ?? []).map(text).join('');
}

/** Replaces each ```live fence with a `div[data-live]` placeholder. An unknown
 *  block name fails the build rather than shipping an empty box. */
export function rehypeLiveBlocks() {
  return (tree: unknown) => {
    visit(tree as never, 'element', (node: HastNodeish, index: number | undefined, parent: HastNodeish | undefined) => {
      if (node.tagName !== 'pre' || !parent?.children || index === undefined) return;
      const code = node.children?.[0];
      const cls = code?.properties?.className;
      if (code?.tagName !== 'code' || !Array.isArray(cls) || !cls.includes('language-live')) return;
      const name = text(code).trim();
      if (!isLiveBlock(name)) {
        throw new Error(`Unknown live docs block "${name}" (known: ${LIVE_BLOCKS.join(', ')})`);
      }
      parent.children[index] = {
        type: 'element',
        tagName: 'div',
        properties: { className: ['doc-live'], dataLive: name },
        children: [{
          type: 'element',
          tagName: 'p',
          properties: { className: ['doc-live-status'] },
          children: [{ type: 'text', value: 'Loading the current rates…' }],
        }],
      };
    });
  };
}
