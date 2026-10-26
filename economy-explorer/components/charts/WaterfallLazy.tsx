'use client';
// The waterfall's recharts plot, loaded like the others in lazy.tsx: only on
// routes that draw it, after first paint, behind a skeleton of its final height.

import dynamic from 'next/dynamic';

function Loading() {
  return <div className="skeleton-chart" role="status" aria-busy="true" aria-label="Loading chart"><span className="skeleton skeleton-chart-plot" /></div>;
}

export const WaterfallPlot = dynamic(() => import('./plots/WaterfallPlot'), { ssr: false, loading: Loading });
