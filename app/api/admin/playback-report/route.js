// POST /api/admin/playback-report — the television telling the server how
// playback actually went.
//
// The server can measure its own throughput but not what happened after the
// bytes left it. Without this half, a stall that the server's own numbers say
// never happened is unattributable: it could be the box's decoder, the Wi-Fi,
// or the player's buffer. With it, the two sides can be compared on one
// timeline in /admin/monitor.
//
// Device-key gated, not session gated: the poster is the TV client, which has
// no cookie. It writes only to the in-memory event ring — nothing here reaches
// the database.

import { requireDeviceKey } from '@/lib/auth.js';
import { recordPlaybackReport } from '@/lib/monitor/metrics.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/** Keeps a malformed or hostile body from filling the event ring with junk. */
function clean(body) {
  const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);
  const str = (v, max = 200) => (typeof v === 'string' ? v.slice(0, max) : null);
  return {
    mediaId: str(body.mediaId, 64),
    title: str(body.title),
    // How many times playback dropped back to buffering after it had started.
    // The single number that says "it lagged".
    rebuffers: num(body.rebuffers),
    rebufferMs: num(body.rebufferMs),
    // Frames the decoder could not keep up with. High here with zero
    // rebuffers means the box, not the network — a distinction no amount of
    // server-side tuning can make.
    droppedFrames: num(body.droppedFrames),
    // What the file actually is, so a stall can be read against the bitrate
    // the link would have needed.
    videoBitrate: num(body.videoBitrate),
    videoFormat: str(body.videoFormat, 120),
    watchedMs: num(body.watchedMs),
    // ExoPlayer's own estimate of the connection it was reading over.
    estimatedBandwidth: num(body.estimatedBandwidth),
    source: str(body.source, 32) || 'tv',
  };
}

export async function POST(request) {
  const authError = requireDeviceKey(request);
  if (authError) return authError;

  let body;
  try {
    body = await request.json();
  } catch {
    return Response.json({ error: 'Invalid JSON' }, { status: 400 });
  }

  recordPlaybackReport(clean(body));
  return Response.json({ ok: true }, { headers: { 'Cache-Control': 'no-store' } });
}
