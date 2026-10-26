// Which requests proxy.ts leaves out of the access log. Player heads (a dozen
// per table page) and static files would drown it. They still pass through the
// request blocker; they just aren't logged.
const STATIC_FILE = /\.(?:png|jpe?g|gif|webp|avif|svg|ico|css|js|map|woff2?|ttf|txt|xml|webmanifest)$/i;

export function isQuietPath(path: string): boolean {
  return path.startsWith('/api/avatar/') || STATIC_FILE.test(path);
}
