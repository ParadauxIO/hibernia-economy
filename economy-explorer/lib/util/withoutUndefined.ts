/**
 * Drop the keys whose value is `undefined`, so an options object built from
 * optional sources (mostly `process.env`) satisfies `exactOptionalPropertyTypes`:
 * a library's `host?: string` means "absent or a string", not "maybe undefined".
 */
export function withoutUndefined<T extends Record<string, unknown>>(
  value: T,
): { [K in keyof T]?: Exclude<T[K], undefined> } {
  return Object.fromEntries(Object.entries(value).filter(([, v]) => v !== undefined)) as {
    [K in keyof T]?: Exclude<T[K], undefined>;
  };
}
