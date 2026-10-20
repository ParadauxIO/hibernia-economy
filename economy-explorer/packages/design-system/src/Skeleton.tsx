// Shimmer placeholders shown by route loading.tsx while a server component
// streams. Pure presentational — reuses the `.skeleton` shimmer in explorer.css.

export function SkeletonLine({ w = '100%', h = 14 }: { w?: string | number; h?: number }) {
  return (
    <span
      className="skeleton"
      style={{
        display: 'block',
        width: typeof w === 'number' ? `${w}px` : w,
        height: h,
        borderRadius: 6,
      }}
    />
  );
}

/** Generic table placeholder — the app's heaviest pages are all data tables. */
export function SkeletonTable({ rows = 8, cols = 5 }: { rows?: number; cols?: number }) {
  return (
    <div className="table-wrap" aria-hidden="true">
      <table className="data-table">
        <tbody>
          {Array.from({ length: rows }).map((_, r) => (
            <tr key={r}>
              {Array.from({ length: cols }).map((_, c) => (
                <td key={c}>
                  <SkeletonLine w={c === 0 ? '55%' : '38%'} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
