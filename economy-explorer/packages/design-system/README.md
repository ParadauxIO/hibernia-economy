# Economy Explorer design system (v2)

`@democracycraft/design-system` is the private, source-distributed npm workspace
package the explorer is built from. Next transpiles it directly; there is no
separate build. Open **`/design-system`** on any running explorer to see every
primitive and chart in every state, plus two pages composed only from them:
`/design-system/mock-account` (add `?view=public` for the logged-out view) and
`/design-system/mock-me`.

The direction is restrained, typographic and data-first. Hierarchy comes from
type size, weight and alignment; sections are separated by whitespace and
hairlines, and only real units get a border. Numbers are the heroes. There is
one accent per brand, and colour carries meaning only (money direction, status,
chart categories). Minecraft flavour comes from player heads and item icons.

## Entry points

| Import | Contents |
| --- | --- |
| `@democracycraft/design-system` | Structure, figures, entities, controls, states, popover base. Works in server and client components. |
| `@democracycraft/design-system/next` | Components that need `next/link` or `next/navigation`: `Pager`, `CursorPager`, `Tabs`, `EntityLink`, `RangePicker`, `SidebarLayout`, `BackLink`. |
| `@democracycraft/design-system/popover` | The floating-panel base: `Popover`, `MenuItem`, `MenuGroup`, `HoverCard`, `Dialog`, `computePosition`. FilterBar and the command palette build on this. |
| `@democracycraft/design-system/format` | `formatNum`, `formatPct`, `formatWhen`, `formatUtc`, `compactMagnitude`. Money formatting is `lib/money.ts`. |
| `@democracycraft/design-system/styles.css` | Tokens, reset, component and primitive styles. Imported once by the root layout. |

Money is the one place the package reaches into the host: `Money` formats
through `@/lib/money` (spec §3 rule 8, a single money module). Nothing else in
the package imports `@/`.

## Page skeleton

```tsx
<PageHeader eyebrow={<EntityBadge type="BUSINESS" />} title="…" subtitle="…" actions={<ExportMenu hrefs={…} />} tabs={<Tabs …/>} />
<RangePicker value={parseRange(searchParams).value} />
<MetricRow label="Summary">
  <MetricCard label="Balance" value={<Money value={row.balance} />} delta={0.12} deltaLabel="vs previous 30 days" />
</MetricRow>
<Section title="Balance" description="Last 30 days"><BalanceLine data={…} /></Section>
<Section title="Transactions" divider>…</Section>
```

Workspaces (`/me`, `/gov`, `/admin`) wrap this in `SidebarLayout`.

## Primitives

| Primitive | Key props | Notes |
| --- | --- | --- |
| `PageHeader` | `title`, `subtitle?`, `eyebrow?`, `avatar?`, `meta?`, `actions?`, `tabs?` | The page's only `h1`. Replaces `PageHeading` and `.page-heading`. |
| `Section` | `title?`, `description?`, `actions?`, `level?: 2 \| 3`, `divider?`, `boxed?`, `id?` | `<section>` with a real heading; named by it when `id` is set. `boxed` only for real units. |
| `MetricRow` / `MetricCard` | Row: `label?`. Card: `label`, `value`, `delta?: number \| null`, `deltaLabel?`, `deltaTone?: 'auto' \| 'inverse' \| 'neutral'`, `info?`, `spark?: number[]`, `meta?` | One hairline panel with dividers, not four boxes. `delta` is a fraction (0.12 = +12%). |
| `Money` | `value: Cents \| string`, `currency?`, `compact?`, `sign?: 'auto' \| 'always' \| 'exceptZero' \| 'never'`, `tone?: 'none' \| 'auto' \| 'inverse'` | Formats through `lib/money`. Compact shows the exact value in a tooltip. Pass `currency` explicitly in client components. |
| `Num`, `Pct`, `Delta` | `Num`: `value`, `compact?`, `unit?`. `Pct`: fraction. `Delta`: fraction, `label?`, `tone?` | Tabular figures. |
| `When` | `value`, `time?`, `relative?` | Relative under 24 h, `Oct 25` this year, `Oct 25, 2025` otherwise. Local time after hydration, UTC on the server; exact UTC in the tooltip. Absorbs `LocalTimestamp`. |
| `EntityBadge`, `Tag` | `type` / `tone` | A dot and a word. Legacy `.badge-PERSONAL` etc. render the same way. |
| `EntityLink` (`/next`) | `kind`, `href`, `name`, `uuid?`, `accountType?`, `id?`, `icon?`, `preview?` | `preview` is a URL returning `EntityPreviewData` JSON; the hover card and its data load on first hover or focus. |
| `PlayerHead` | `uuid`, `size?`, `name?`, `alt?` | Served by the host's `/api/avatar/[uuid]` proxy (24 h cache); visitors never contact the head service. |
| `RangePicker` (`/next`) | `value: RangeValue`, `presets?`, `allowCompare?`, `min?`, `max?`, `resetParams?` | Writes `range`, `from`, `to`, `cmp`; drops `page`. Parse with `lib/range.ts`. |
| `Segmented` | `items`, `value`, `label`, `onChange?` | Links with `aria-current`, or toggle buttons with `aria-pressed`. Replaces `WindowTabs`. |
| `Tabs` (`/next`) | `items: { label, href, current?, count? }[]`, `label` | Route tabs under a `PageHeader`. |
| `Pager` / `CursorPager` (`/next`) | `Pager`: as `Pagination` plus `noun?`. `CursorPager`: `newerHref`, `olderHref`, `jump?`, `summary?` | Server-rendered links. |
| `ExportMenu` | `hrefs: { page?: { csv?, json? }, all?: { csv?, json? } }`, `allDescription?` | UI only; the host builds and authorizes the URLs. |
| `EmptyState`, `ErrorState`, `AccessGate` | `AccessGate`: `kind: 'login' \| 'private' \| 'link' \| 'request'`, `title`, `description?`, `action?`, `grants?`, `inline?` | `request` lists the relationships that grant access. The host supplies the login form. |
| `Skeleton*` | `SkeletonLine`, `SkeletonText`, `SkeletonTable`, `SkeletonMetricRow`, `SkeletonChart` | `SkeletonChart` is `aria-busy` until replaced; test helpers wait for it. |
| `SidebarLayout` (`/next`) | `title`, `nav: { label?, items: { label, href, current?, count? }[] }[]` | Left navigation; a select on phones. |
| `Popover` (`/popover`) | `trigger`, `children` (or `({ close }) => …`), `label?`, `role?: 'dialog' \| 'menu' \| 'listbox'`, `placement?`, `open?`/`onOpenChange?`, `sheet?`, `minWidth?` | Non-modal. Focus moves in once the panel is positioned and visible: the first menu item, a `[data-autofocus]` control, or the first focusable element. Escape, outside click or tabbing away closes it; focus returns to the trigger. Portal + `position: fixed`, flipped and clamped to the viewport. `sheet` opens a bottom sheet at ≤ 720 px. |
| `HoverCard`, `Dialog` (`/popover`) | `HoverCard`: `trigger`, `onFirstOpen?`. `Dialog`: `open`, `onClose`, `title`, `description?`, `actions?`, `size?` | `Dialog` is the native `<dialog>`: focus trap, inert page and Escape come from the browser. |

Legacy components (`PageHeading`, `StatCard`, `Badge`, `ChartCard`, `WindowTabs`,
`SectionNav`, `Pagination`) still work and are restyled to match, so pages look
consistent until each is rebuilt. Don't use them in new code.

## Tokens

All in `styles/tokens.css`, in four blocks (blue/red × dark/light).
`test/unit/tokens.test.ts` checks the cascade and contrast for every combination.

- **Type:** `--text-xs` … `--text-3xl` with matching `--leading-*` (12/16, 13/18, 14/20, 16/24, 20/28, 24/32, 32/40). Weights 400, 500, 600.
- **Spacing:** `--space-N` = N × 4 px for N in 1, 2, 3, 4, 5, 6, 8, 10, 12, 16. `--section-gap` = `--space-6`.
- **Radius:** `--radius-sm` 6, `--radius-md` 10, `--radius-lg` 14, `--radius-pill`. Nothing else.
- **Elevation:** `--shadow-1`, `--shadow-2`, for floating panels only.
- **Colour:** surfaces and text as before; `--accent`, `--accent-fill`, `--on-accent`; `--pos` / `--neg` for money direction (the red brand uses teal / amber, never green or brand red); `--good` / `--warn` / `--bad` for status; `--cat-1…10` chart categories; `--seq-0…5` sequential (0 = empty).
- Legacy names (`--radius`, `--c-1…7`, `--accent-2`, `--brand-*`) are aliases.

Inline `fontSize`, `padding` and `margin` in JSX `style` props are a lint error in
`packages/**` and `components/charts/**` (Wave 2 widens this to pages).

## Charts

Charts are app components in `components/charts/` (they need app data types);
they share `ChartFrame`, `kit.ts` and the tokens above. Import them from
`@/components/charts/AreaTimeline` (the barrel) or their own files.

- Generic: `TimeSeriesChart` (area or line, stacked or not, second axis), `BarChart` (grouped, stacked, horizontal), `Donut`, `Waterfall`. Server-rendered: `Heatmap`, `Candlestick`; `MiniSpark` from the package.
- Recharts loads through `next/dynamic` only; the server renders a skeleton at the chart's final height.
- Every chart has an `aria-label` summary and a "View chart data" table, legends in the same place that toggle series, monotone curves, axis widths fitted to their labels, nice ticks with compact money, and outlier clamping with a log-scale toggle.

## Verification

From `economy-explorer/`: `npm run typecheck`, `npm run lint`, `npm test`,
`npm run test:e2e` (includes `test/e2e/charts.spec.ts` and axe on the gallery in
all four theme × mode combinations).
