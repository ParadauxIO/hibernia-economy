/**
 * Parse a `?days=N` trailing-window query param.
 *
 * The in-game `/firm sales export <firm> <days>` command deep-links to
 * `/chestshop/firms/<id>?days=N`, so the firm drilldown has to honour N rather
 * than silently showing all-time totals.
 *
 * Returns the window in whole days, or `null` for "all time" — which is what an
 * absent or malformed value means (anything that isn't a plain positive
 * integer: empty, signed, decimal, exponent, non-numeric). A valid value above
 * {@link MAX_DAYS_WINDOW} is clamped to it, so the caller's UI always states
 * the window that was actually applied.
 */
export const MAX_DAYS_WINDOW = 3650;

export function parseDaysWindow(raw: string | undefined): number | null {
  if (raw === undefined) return null;
  const s = raw.trim();
  // Digits only: rejects '', '-5', '1.5', '1e3', '0x10', '30d'.
  if (!/^\d{1,9}$/.test(s)) return null;
  const n = Number(s);
  if (n < 1) return null;
  return Math.min(n, MAX_DAYS_WINDOW);
}
