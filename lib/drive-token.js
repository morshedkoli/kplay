// lib/drive-token.js — the short-lived Drive access token handed to players.
//
// Every play fetches a token, and a long viewing session fetches another each
// hour. Minting one per request would refresh against Google far more often
// than the token's life requires, so the last one is held here and reused
// until it is close enough to expiry that a range request issued with it might
// outlive it.

// How early a token is treated as spent. A range request issued at the very
// edge of a token's life can arrive at Google after it has expired, and the
// player sees that as a failed seek rather than as a retryable auth error.
export const REFRESH_MARGIN_MS = 5 * 60 * 1000;

let cached = null;
let inFlight = null;

/** true when a token expiring at `expiresAt` should no longer be handed out. */
export function isStale(expiresAt, now = Date.now()) {
  return !expiresAt || expiresAt - now <= REFRESH_MARGIN_MS;
}

/**
 * The current access token, minting a new one only when the held one is stale.
 *
 * `mint` is injected rather than imported so this module stays free of the
 * googleapis client and can be tested without one.
 */
export async function cachedAccessToken(mint, now = Date.now()) {
  if (cached && !isStale(cached.expiresAt, now)) return cached;

  // A cold start under load calls this from several requests at once. Sharing
  // the promise keeps that to a single refresh; without it each caller starts
  // its own and the last one to resolve wins arbitrarily.
  if (!inFlight) {
    inFlight = (async () => {
      try {
        cached = await mint();
        return cached;
      } finally {
        inFlight = null;
      }
    })();
  }

  return inFlight;
}

/** Drops the held token. Tests only — nothing in the app needs this. */
export function resetTokenCache() {
  cached = null;
  inFlight = null;
}
