'use client';

import type { EntityPreviewData } from './EntityPreview';

export default function EntityPreviewBody({ data }: { data: EntityPreviewData }) {
  return <div className="hover-card-body">
    <p className="hover-card-title">{data.title}</p>
    {data.subtitle && <p className="hover-card-subtitle">{data.subtitle}</p>}
    {data.rows && data.rows.length > 0 && <dl className="hover-card-rows">
      {data.rows.slice(0, 4).map((row) => <div key={row.label}><dt>{row.label}</dt><dd>{row.value}</dd></div>)}
    </dl>}
  </div>;
}
