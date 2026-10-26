import { isMetricsRequestAuthorised, metricsText } from '@/lib/metrics';

// Prometheus scrape endpoint (PAR-531). Not public: it answers only a request
// carrying `Authorization: Bearer $METRICS_TOKEN`, and is a plain 404 to
// everyone else, including when no token is configured.
export const dynamic = 'force-dynamic';

const notFound = () =>
  new Response('Not Found', { status: 404, headers: { 'content-type': 'text/plain', 'cache-control': 'no-store' } });

export async function GET(req: Request): Promise<Response> {
  if (!isMetricsRequestAuthorised(req.headers.get('authorization'), process.env.METRICS_TOKEN)) return notFound();
  const { body, contentType } = await metricsText();
  return new Response(body, { status: 200, headers: { 'content-type': contentType, 'cache-control': 'no-store' } });
}
