import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
export function DirectorySort({ basePath, params, sort, dir, options }: {
  basePath: AppHref; params: Record<string, string | string[] | undefined>; sort: string; dir: string;
  options: { label: string; sort: string; dir: string }[];
}) {
  const label = options.find(o => o.sort === sort && o.dir === dir)?.label ?? `${sort === 'account_id' ? 'Account number' : sort} (${dir === 'ASC' ? 'ascending' : 'descending'})`;
  return <nav className="directory-sort" aria-label="Sort results"><span>Sorted by: <strong>{label}</strong></span>
    {options.map(o => {
      const query = new URLSearchParams(Object.entries(params).flatMap(([k, v]) => typeof v === 'string' && k !== 'page' ? [[k, v]] : []));
      query.set('sort', o.sort); query.set('dir', o.dir);
      const active = o.sort === sort && o.dir === dir;
      return <Link key={o.label} href={`${basePath}?${query}`} className={`btn ${active ? 'btn-primary' : ''}`} aria-current={active ? 'true' : undefined}>{o.label}</Link>;
    })}
  </nav>;
}
