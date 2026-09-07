// public/drive-sw.js — turns /drive/<fileId> into an authenticated Drive read.
//
// A <video> element cannot send an Authorization header, and Drive will not
// accept the token any other way, so the element points at a same-origin path
// and this worker performs the real request. Native seeking survives, because
// the browser still issues ordinary range requests and still receives 206s.
//
// The token lives here rather than in the page: nothing on the page needs it,
// and a worker's scope is a smaller place for a credential to sit.

const DRIVE_PATH = /^\/drive\/([^/?#]+)$/;

let token = null;
let expiresAt = 0;

// The same margin lib/drive-token.js uses. A range request issued at the very
// edge of a token's life can arrive after it has expired, and the player reads
// that as a broken seek rather than as something to retry.
const REFRESH_MARGIN_MS = 5 * 60 * 1000;

async function currentToken() {
  if (token && Date.now() < expiresAt - REFRESH_MARGIN_MS) return token;

  const res = await fetch('/api/drive/token', { credentials: 'include' });
  if (!res.ok) throw new Error(`token -> ${res.status}`);

  const body = await res.json();
  token = body.token;
  expiresAt = body.expiresAt;
  return token;
}

async function proxyToDrive(request, driveFileId) {
  const headers = new Headers();
  headers.set('Authorization', `Bearer ${await currentToken()}`);

  const range = request.headers.get('Range');
  if (range) headers.set('Range', range);

  const url = `https://www.googleapis.com/drive/v3/files/${driveFileId}?alt=media`;
  let res = await fetch(url, { headers });

  // A token can expire between the check above and Drive reading it. One
  // forced refresh distinguishes that from a real authorisation failure.
  if (res.status === 401) {
    token = null;
    headers.set('Authorization', `Bearer ${await currentToken()}`);
    res = await fetch(url, { headers });
  }

  return res;
}

self.addEventListener('install', (event) => {
  event.waitUntil(self.skipWaiting());
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;

  const match = DRIVE_PATH.exec(url.pathname);
  if (!match) return;

  event.respondWith(
    proxyToDrive(event.request, match[1]).catch(
      (err) => new Response(`Drive read failed: ${err.message}`, { status: 502 })
    )
  );
});
