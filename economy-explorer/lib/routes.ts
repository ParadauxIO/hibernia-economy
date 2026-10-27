import type { Route } from 'next';
import type { AppRoutes, AppRouteHandlerRoutes } from '../.next/types/routes';

// Expand Next's filesystem parameters for data-driven links. Route still checks
// the resulting paths against Next's generated navigation contract.
type Expand<Path extends string> = Path extends `${infer Head}[[...${string}]]${infer Tail}`
  ? `${Head}${string}${Expand<Tail>}`
  : Path extends `${infer Head}[${string}]${infer Tail}`
    ? `${Head}${string}${Expand<Tail>}`
    : Path;
export type AppHref = Route<Expand<AppRoutes | AppRouteHandlerRoutes> | `${Expand<AppRoutes | AppRouteHandlerRoutes>}?${string}` | `${Expand<AppRoutes | AppRouteHandlerRoutes>}#${string}`>;
