// GET /api/admin/metrics — everything the /admin/monitor dashboard draws.
//
// Session-gated rather than device-key gated: this reports process memory,
// host load and what is currently being watched, none of which a television
// needs and none of which should be reachable without signing in.

import { isAdmin } from '@/lib/admin-auth.js';
import { ensureSampler, snapshot } from '@/lib/monitor/metrics.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET() {
  if (!(await isAdmin())) {
    return Response.json({ error: 'Unauthorized' }, { status: 401 });
  }

  // Started here rather than at import time: a build-time module evaluation
  // would otherwise leave a timer running in the build container, and a
  // process that never serves the dashboard has no reason to sample at all.
  ensureSampler();

  return Response.json(snapshot(), { headers: { 'Cache-Control': 'no-store' } });
}
