import { getDocsTree } from '@/lib/docs';
import { DocsSidebar } from '@/components/docs/DocsSidebar';
import { CodeCopyButton } from '@/components/docs/CodeCopyButton';
import { ApiServerHint } from '@/components/docs/ApiServerHint';
import { LiveDocBlocks } from '@/components/docs/LiveDocBlocks';

// Three-column docs shell (sidebar · content · on-this-page TOC), nested inside
// the app's root layout so it keeps the shared header/footer. The page renders
// the middle <article> and the right-hand <DocsToc> as the two remaining grid
// columns; the sidebar is the first.
export default async function DocsLayout({ children }: { children: React.ReactNode }) {
  const tree = await getDocsTree();
  return (
    <div className="docs-layout">
      <DocsSidebar tree={tree} />
      {children}
      <CodeCopyButton />
      <ApiServerHint />
      <LiveDocBlocks />
    </div>
  );
}
