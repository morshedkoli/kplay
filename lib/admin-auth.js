// Password gate for the browser-facing app (gallery, upload, storage, /admin).
//
// A device-key header can't be sent by a plain browser navigation, so the web
// UI signs in with ADMIN_PASSWORD once and carries an HttpOnly session cookie
// afterwards. The cookie holds an HMAC of the password, never the password
// itself — a leaked cookie can't be replayed as a password anywhere else.

import { createHmac, timingSafeEqual } from 'node:crypto';
import { cookies } from 'next/headers';

import { ADMIN_COOKIE } from './session-cookie.js';

export { ADMIN_COOKIE };

const SESSION_LABEL = 'kdrive-session-v1';

function equals(a, b) {
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  if (bufA.length !== bufB.length) return false;
  return timingSafeEqual(bufA, bufB);
}

export function checkPassword(candidate) {
  const expected = process.env.ADMIN_PASSWORD;
  if (!expected || !candidate) return false;
  return equals(candidate, expected);
}

/** The value stored in the cookie for a valid session. */
export function sessionToken() {
  const secret = process.env.ADMIN_PASSWORD;
  if (!secret) return null;
  return createHmac('sha256', secret).update(SESSION_LABEL).digest('hex');
}

/** true when this cookie value represents a signed-in session. */
export function isValidSession(value) {
  if (!value) return false;
  const token = sessionToken();
  if (!token) return false;
  // Cookies issued before the HMAC change stored the raw password; accept
  // those too so an open tab doesn't get logged out mid-session.
  return equals(value, token) || checkPassword(value);
}

/** true when the current request carries a valid session cookie. */
export async function isAdmin() {
  const store = await cookies();
  return isValidSession(store.get(ADMIN_COOKIE)?.value || '');
}

export function adminCookieOptions() {
  return {
    httpOnly: true,
    sameSite: 'lax',
    // Whole app, not just /admin — the gallery and upload pages need it too.
    path: '/',
    maxAge: 60 * 60 * 24 * 30,
    secure: process.env.NODE_ENV === 'production',
  };
}
