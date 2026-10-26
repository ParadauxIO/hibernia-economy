'use client';
// recharts enters the bundle only through these dynamic imports, so a route
// without charts never downloads it and a route with charts paints first and
// loads it after. The server renders the loading skeleton; the wrapper
// (ChartFrame's .chart-plot) has the chart's final height, so nothing moves.

import dynamic from 'next/dynamic';

function Loading() {
  return <div className="skeleton-chart" role="status" aria-busy="true" aria-label="Loading chart"><span className="skeleton skeleton-chart-plot" /></div>;
}

export const TimeSeriesPlot = dynamic(() => import('./plots/TimeSeriesPlot'), { ssr: false, loading: Loading });
export const BarPlot = dynamic(() => import('./plots/BarPlot'), { ssr: false, loading: Loading });
export const DonutPlot = dynamic(() => import('./plots/DonutPlot'), { ssr: false, loading: Loading });
