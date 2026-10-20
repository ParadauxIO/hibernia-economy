# Economy Explorer design system

`@democracycraft/design-system` is a private, source-distributed npm workspace
package for the DemocracyCraft and StateCraft explorers. Run `npm ci` from
`economy-explorer/`; Next transpiles its TypeScript directly. No separate build,
registry publication, or new runtime dependency is required.

## Use

Import the stylesheet once in the host's root layout, before application CSS:

```tsx
import '@democracycraft/design-system/styles.css';
import { Button, Card, PageHeading, StatCard, TableWrap } from '@democracycraft/design-system';
import { Pagination, BackLink } from '@democracycraft/design-system/next';
```

The root entry contains React presentation components and works in server or
client components. `/next` isolates Next Link adapters. `/use-hydrated` is an
explicit client entry; the root barrel does not turn the application into a
client component. The package never imports `@/`, application services, sessions,
permissions, database modules, or business formatters.

| Layer | Contents |
| --- | --- |
| Foundations | CSS custom properties for surface, text, brand, status, chart series, fonts, radius, motion and section spacing; reset and type styles |
| Layout | `PageHeading`, `Card`, `ChartCard`, `StatCard`, `SectionNav`, `ToolbarLayout` |
| Controls | `Button`, `Input`, `Select`, `Badge` |
| Data and states | `TableWrap`, `EmptyState`, `SkeletonLine`, `SkeletonTable`, `InfoTip`, `MiniSpark` |
| Navigation | `WindowTabs`; Next adapters `BackLink`, `Pagination` |
| Hydration | `useHydrated` for controls and DOM-measured charts |

Set `data-mode="dark|light"` and `data-theme="blue|red"` on the document root.
The host owns preference persistence and setting these attributes before first
paint. Geist variables are optional: fonts have system fallbacks. Consumers that
only need variables can import `/tokens.css`; `/styles.css` also includes global
reset and native-control styles, so it is intended for an explorer-style host.

Native control attributes, events and React 19 refs pass through. `Button`
defaults to `type="button"`; use `type="submit"` for forms. `TableWrap` requires
a region label and keeps overflowing tables keyboard-focusable. Provide visible
labels or accessible names for inputs and meaningful text next to decorative
sparklines. `SectionNav` accepts host-created links; routing and permission
filtering remain the host's responsibility.

## Ownership and migration

Existing class names are retained for incremental migration. Shared
component CSS, including mobile rules, lives in `styles/components.css`; shell,
market, documentation, charts and permission-gate styles remain in
`app/explorer.css`. Older page markup may still use shared classes directly.
Replace it with exported components as those screens are revised; do not copy
their CSS back into the app.

All callers of the moved components use package imports. The overview consumes
the card, heading and metric primitives; the market uses the controls and empty
state; URL-aware toolbars compose the presentation controls. Search parameter
handling, export authorization, CSV escaping, chart data, prices, and viewer
permissions remain in the app.

Keep application-specific concepts out of the generic API. For example,
`StatCard` receives formatted React content rather than computing money or period
comparisons, and `Badge` receives host-selected classes rather than deciding an
account's type or status. Rich charts and shop offers remain domain components.

## Review and verification

Open `/design-system` after starting the explorer. The gallery uses fictional
fixtures and does not need a database. It includes all four brand/appearance
combinations, cards, controls, a working search/filter/pagination journey,
horizontal table scrolling, a sparkline, tooltips, loading and empty states.
Appearance previews do not overwrite saved preferences and restore the previous
document appearance when the gallery unmounts.

From `economy-explorer/`:

```sh
npm run typecheck
npm run lint
npm test
npm run build
```

The existing component suite exercises the URL adapters, permissions and exports
against the package-backed controls. Coverage includes package source. Docker's
dependency stage copies the workspace manifest before `npm ci`; CI continues to
use the explorer's existing lockfile and build context.

See [the design audit](../../design/design-audit.md) for screen-level priorities
and linked Mobbin references. The extraction is a foundation, not a claim that
all legacy markup or accessibility issues have been redesigned.

## Interaction refinements

The shared tokens now include stronger light-mode text/link contrast and
consistent mobile control sizes. Time selectors use visible day labels and
current-state semantics. Help opens on hover, focus or tap, stays within the
viewport, and dismisses on Escape without moving focus. Reduced-motion users
receive immediate state changes.

Application toolbars compose these foundations with URL-aware filter chips and
reset behavior. Full Market and Economy screen composition lives in
`app/experience.css`; offer availability, price normalization and return-route
validation remain application responsibilities.
