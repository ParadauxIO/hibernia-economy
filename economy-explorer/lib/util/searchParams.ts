/**
 * Flatten a Next.js `searchParams` bag to a plain `Record<string, string>`.
 *
 * Next.js hands each key either a single `string`, a `string[]` (when the key
 * repeats in the URL, e.g. `?tag=a&tag=b`), or `undefined`. Pages that read
 * scalar params want the single-string form, so we collapse each key to its
 * first defined value and drop absent keys. Repeated keys keep their first
 * occurrence; `undefined` keys are omitted.
 *
 * Consolidates the ~18 copy-pasted `flat`/`flatten` helpers that used to live in
 * the individual page files.
 */
export function flattenSearchParams(
  raw: Record<string, string | string[] | undefined>,
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(raw)) {
    if (Array.isArray(v)) {
      if (v.length > 0 && v[0] !== undefined) out[k] = v[0];
    } else if (v !== undefined) {
      out[k] = v;
    }
  }
  return out;
}
