// GET /api/drive/token — the credential a player needs to read bytes from
// Drive itself.
//
// This is the whole of what the server contributes to playback. The client
// takes the token, fetches ranges straight from Google, and refreshes here
// before the token expires; no file bytes pass through this deployment, which
// is the property the whole architecture rests on.
//
// There is deliberately no proxy fallback behind this route. If minting fails,
// playback fails, and that is visible rather than silently expensive.

import { requireDeviceOrSession } from '@/lib/auth.js';
import { getAccessToken } from '@/lib/gdrive.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  let token;
  let expiresAt;
  try {
    ({ token, expiresAt } = await getAccessToken());
  } catch (err) {
    console.error('[api/drive/token] mint failed', err);
    return Response.json({ error: 'Storage auth failed' }, { status: 503 });
  }

  return Response.json(
    { token, expiresAt },
    {
      // A live credential. It must never reach a disk cache, a shared proxy,
      // or a browser's back-forward store.
      headers: { 'Cache-Control': 'private, no-store', Pragma: 'no-cache' },
    }
  );
}
