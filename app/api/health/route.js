// GET /api/health — liveness probe for the container healthcheck and for the
// nginx upstream. Deliberately unauthenticated and deliberately silent about
// configuration: it reports only that the process is up and serving, never
// which env vars are set or what the database is.

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export function GET() {
  return Response.json({ ok: true }, { headers: { 'Cache-Control': 'no-store' } });
}
