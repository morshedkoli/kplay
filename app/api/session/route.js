// POST /api/session   — sign in with ADMIN_PASSWORD, sets the session cookie
// DELETE /api/session — sign out
//
// The web app's only auth. Single user (PRD section 4), so there is no user
// record — the password IS the account.

import { cookies } from 'next/headers';

import { ADMIN_COOKIE, adminCookieOptions, checkPassword, sessionToken } from '@/lib/admin-auth.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

// Blunt rate limit: this process only, reset on restart. Enough to make an
// online guess of the password impractical without dragging in a store.
const attempts = new Map(); // ip -> { count, first }
const WINDOW_MS = 5 * 60 * 1000;
const MAX_ATTEMPTS = 10;

function tooManyAttempts(ip) {
  const now = Date.now();
  const entry = attempts.get(ip);

  if (!entry || now - entry.first > WINDOW_MS) {
    attempts.set(ip, { count: 1, first: now });
    return false;
  }
  entry.count += 1;
  return entry.count > MAX_ATTEMPTS;
}

export async function POST(request) {
  if (!process.env.ADMIN_PASSWORD) {
    return Response.json(
      { error: 'ADMIN_PASSWORD is not set on the server' },
      { status: 503 }
    );
  }

  const ip = request.headers.get('x-forwarded-for')?.split(',')[0].trim() || 'local';
  if (tooManyAttempts(ip)) {
    return Response.json({ error: 'Too many attempts — wait a few minutes' }, { status: 429 });
  }

  let password;
  try {
    ({ password } = await request.json());
  } catch {
    return Response.json({ error: 'Expected JSON body' }, { status: 400 });
  }

  if (typeof password !== 'string' || !checkPassword(password)) {
    return Response.json({ error: 'Wrong password' }, { status: 401 });
  }

  attempts.delete(ip);
  const store = await cookies();
  store.set(ADMIN_COOKIE, sessionToken(), adminCookieOptions());
  return Response.json({ ok: true });
}

export async function DELETE() {
  const store = await cookies();
  store.set(ADMIN_COOKIE, '', { ...adminCookieOptions(), maxAge: 0 });
  return Response.json({ ok: true });
}
