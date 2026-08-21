// Shared-secret auth for the device-facing routes.
//
// Single-user app: a non-browser API client sends a fixed key in a header, compared
// against KDRIVE_DEVICE_KEY. The browser client can't set headers on <img> or
// navigation requests, so it authenticates with the session cookie instead —
// see requireDeviceOrSession().

import { timingSafeEqual } from 'node:crypto';
import { cookies } from 'next/headers';

import { ADMIN_COOKIE, isValidSession } from './admin-auth.js';

export const DEVICE_KEY_HEADER = 'x-kdrive-device-key';
const LEGACY_HEADER = 'x-bhandar-device-key'; // pre-rename clients

function equals(a, b) {
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  if (bufA.length !== bufB.length) return false;
  return timingSafeEqual(bufA, bufB);
}

/** true when the request carries the correct device key header. */
export function hasDeviceKey(request) {
  const expected = process.env.KDRIVE_DEVICE_KEY;
  if (!expected) return false;

  const provided =
    request.headers.get(DEVICE_KEY_HEADER) || request.headers.get(LEGACY_HEADER) || '';
  return Boolean(provided) && equals(provided, expected);
}

/**
 * Returns null when the request is authorised, or a Response to return as-is.
 */
export function requireDeviceKey(request) {
  const expected = process.env.KDRIVE_DEVICE_KEY;
  if (!expected) {
    console.error('[auth] KDRIVE_DEVICE_KEY is not set — refusing all requests');
    return Response.json({ error: 'Server auth not configured' }, { status: 500 });
  }

  if (!hasDeviceKey(request)) {
    return Response.json({ error: 'Unauthorized' }, { status: 401 });
  }
  return null;
}

/**
 * Accepts either the device key header or a signed-in browser session.
 * Returns null when authorised, or a Response to return as-is.
 *
 * Use this on every route the web app calls; use requireDeviceKey() only where
 * a browser has no business reaching.
 */
export async function requireDeviceOrSession(request) {
  if (hasDeviceKey(request)) return null;

  const store = await cookies();
  if (isValidSession(store.get(ADMIN_COOKIE)?.value || '')) return null;

  if (!process.env.KDRIVE_DEVICE_KEY && !process.env.ADMIN_PASSWORD) {
    console.error('[auth] neither KDRIVE_DEVICE_KEY nor ADMIN_PASSWORD is set');
    return Response.json({ error: 'Server auth not configured' }, { status: 500 });
  }
  return Response.json({ error: 'Unauthorized' }, { status: 401 });
}
