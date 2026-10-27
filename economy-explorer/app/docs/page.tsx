import DocPage, { generateMetadata as docMetadata } from './[...slug]/page';

export function generateMetadata() {
  return docMetadata({ params: Promise.resolve({ slug: [] }) });
}

export default function DocsIndex() {
  return <DocPage params={Promise.resolve({ slug: [] })} />;
}
