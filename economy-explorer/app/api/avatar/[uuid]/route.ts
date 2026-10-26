// Player head proxy for <PlayerHead>. The browser only ever talks to us: the
// upstream head service sees this server, never a visitor's IP, cookies or
// referrer. Responses are cached for 24 hours here (fetch data cache) and in
// the browser.

const UUID = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;
const SIZES = [16, 24, 32, 48, 64, 96, 128];
const DAY_S = 86_400;
const MAX_BYTES = 256 * 1024;
/** `{uuid}` and `{size}` are substituted. Override with AVATAR_URL_TEMPLATE. */
const DEFAULT_TEMPLATE = 'https://mc-heads.net/avatar/{uuid}/{size}';

const CACHE = `public, max-age=${DAY_S}, stale-while-revalidate=${7 * DAY_S}`;

/** A neutral head shown when the service is down or doesn't know the player. Short cache so it recovers. */
function fallback(size: number): Response {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 8 8" shape-rendering="crispEdges"><rect width="8" height="8" fill="#8a8f98"/><rect x="1" y="3" width="2" height="1" fill="#e9ebee"/><rect x="5" y="3" width="2" height="1" fill="#e9ebee"/><rect x="3" y="5" width="2" height="1" fill="#5b6270"/></svg>`;
  return new Response(svg, { status: 200, headers: { 'Content-Type': 'image/svg+xml', 'Cache-Control': 'public, max-age=300', 'X-Content-Type-Options': 'nosniff' } });
}

export function avatarUpstreamUrl(uuid: string, size: number, template = process.env.AVATAR_URL_TEMPLATE?.trim() || DEFAULT_TEMPLATE): string {
  return template.replace('{uuid}', uuid).replace('{size}', String(size));
}

export async function GET(request: Request, { params }: { params: Promise<{ uuid: string }> }): Promise<Response> {
  const { uuid: raw } = await params;
  if (!UUID.test(raw)) return new Response('Invalid player UUID', { status: 400, headers: { 'Cache-Control': 'no-store' } });
  const uuid = raw.toLowerCase().replaceAll('-', '');
  const requested = Number(new URL(request.url).searchParams.get('size') ?? 64);
  const size = SIZES.find((s) => s >= requested) ?? 128;

  try {
    // A fresh request: no visitor headers are forwarded, only what fetch sends itself.
    const upstream = await fetch(avatarUpstreamUrl(uuid, size), {
      headers: { accept: 'image/png' },
      redirect: 'follow',
      signal: AbortSignal.timeout(4000),
      next: { revalidate: DAY_S },
    });
    const type = upstream.headers.get('content-type') ?? '';
    if (!upstream.ok || !type.startsWith('image/png')) return fallback(size);
    const body = await upstream.arrayBuffer();
    if (body.byteLength === 0 || body.byteLength > MAX_BYTES) return fallback(size);
    return new Response(body, { status: 200, headers: { 'Content-Type': 'image/png', 'Cache-Control': CACHE, 'X-Content-Type-Options': 'nosniff' } });
  } catch {
    return fallback(size);
  }
}
