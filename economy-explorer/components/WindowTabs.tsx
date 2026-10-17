// Day-window selector chips for the dashboard pages (Health, Government,
// Money-flow, Market). Plain anchors — a window change is a full SSR reload.
export function WindowTabs({
  basePath,
  days,
  windows = [7, 30],
  params = {},
}: {
  basePath: string;
  days: number;
  windows?: number[];
  params?: Record<string, string | undefined>;
}) {
  return (
    <span className="window-tabs">
      {windows.map((w) => (
        <a key={w} href={`${basePath}?${new URLSearchParams({ ...Object.fromEntries(Object.entries(params).filter((entry): entry is [string, string] => entry[1] !== undefined)), days: String(w) })}`} className={w === days ? 'active' : ''}>
          {w}d
        </a>
      ))}
    </span>
  );
}
