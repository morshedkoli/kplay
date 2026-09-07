// node lib/drive-token.test.js
import assert from 'node:assert/strict';
import { cachedAccessToken, isStale, REFRESH_MARGIN_MS, resetTokenCache } from './drive-token.js';

// A token with plenty of life left is not stale.
{
  const now = 1_000_000;
  assert.equal(isStale(now + 30 * 60 * 1000, now), false);
  console.log('ok: a fresh token is not stale');
}

// A token inside the refresh margin is stale, so playback never carries a
// token that dies mid-request.
{
  const now = 1_000_000;
  assert.equal(isStale(now + REFRESH_MARGIN_MS - 1, now), true);
  console.log('ok: a token inside the refresh margin is stale');
}

// An already-expired token is stale.
{
  const now = 1_000_000;
  assert.equal(isStale(now - 1, now), true);
  console.log('ok: an expired token is stale');
}

// The second call inside the token's life reuses the first mint.
{
  resetTokenCache();
  const now = 1_000_000;
  let mints = 0;
  const mint = async () => {
    mints += 1;
    return { token: `t${mints}`, expiresAt: now + 60 * 60 * 1000 };
  };

  const first = await cachedAccessToken(mint, now);
  const second = await cachedAccessToken(mint, now + 60 * 1000);

  assert.equal(first.token, 't1');
  assert.equal(second.token, 't1');
  assert.equal(mints, 1);
  console.log('ok: a live cached token is reused');
}

// Once the cached token enters the refresh margin, the next call mints again.
{
  resetTokenCache();
  const now = 1_000_000;
  let mints = 0;
  const mint = async () => {
    mints += 1;
    return { token: `t${mints}`, expiresAt: now + mints * 60 * 60 * 1000 };
  };

  await cachedAccessToken(mint, now);
  const later = await cachedAccessToken(mint, now + 60 * 60 * 1000 - REFRESH_MARGIN_MS + 1);

  assert.equal(later.token, 't2');
  assert.equal(mints, 2);
  console.log('ok: a stale cached token is replaced');
}

// Concurrent callers share one refresh rather than each starting their own.
{
  resetTokenCache();
  const now = 1_000_000;
  let mints = 0;
  const mint = async () => {
    mints += 1;
    await new Promise((resolve) => setTimeout(resolve, 10));
    return { token: `t${mints}`, expiresAt: now + 60 * 60 * 1000 };
  };

  const [a, b] = await Promise.all([cachedAccessToken(mint, now), cachedAccessToken(mint, now)]);

  assert.equal(a.token, b.token);
  assert.equal(mints, 1);
  console.log('ok: concurrent callers share one refresh');
}
