import { describe, it, expect } from 'vitest';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkRehype from 'remark-rehype';
import rehypeStringify from 'rehype-stringify';
import { rehypeLiveBlocks } from '@/lib/docsLive';

const render = (md: string) =>
  unified().use(remarkParse).use(remarkRehype).use(rehypeLiveBlocks).use(rehypeStringify).process(md).then(String);

describe('rehypeLiveBlocks', () => {
  it('turns a ```live fence into a placeholder for the client', async () => {
    const html = await render('Rates:\n\n```live\nbalance-tax\n```\n');
    expect(html).toContain('<div class="doc-live" data-live="balance-tax"><p class="doc-live-status">');
    expect(html).not.toContain('<pre>');
  });

  it('leaves other code blocks alone', async () => {
    const html = await render('```yaml\ntax: {}\n```\n');
    expect(html).toContain('<pre><code class="language-yaml">');
  });

  it('fails the build on an unknown block name', async () => {
    await expect(render('```live\nbalance-taxes\n```\n')).rejects.toThrow(/Unknown live docs block "balance-taxes"/);
  });
});
