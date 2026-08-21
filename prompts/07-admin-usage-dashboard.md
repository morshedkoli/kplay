# Prompt 07 — Admin usage dashboard

**Feed this to Claude Code / Antigravity after Prompt 05.**

---

## Context

A single internal page showing how full each of the 12 backends is, so I
can see at a glance whether the pool is filling up and when Telegram
overflow is likely to kick in. Personal tool, no need for fancy auth beyond
what already protects the API routes.

## Task

1. Create `app/admin/usage/page.js` (Next.js App Router page,
   server component) that calls `/api/backup/usage` and renders:
   - A simple bar per provider: name, used/quota (or "unlimited" for
     Telegram), percentage filled.
   - Total pool free space remaining, and an estimated "days until full at
     current rate" if there's enough historical data (skip this
     estimate if there isn't — don't fabricate a number).
   - A visual flag (red badge) on any provider whose `status` in
     `storage_accounts` is `error` or `disabled`.
2. Style it with plain Tailwind (already available if the Next.js scaffold
   includes it — check `package.json`; if Tailwind isn't set up,
   add it). Keep it simple — this is a personal utility page, not a
   polished product UI. No need to follow Bhandar's admin design system
   for this internal page.
3. Protect this route behind the same `x-kdrive-device-key` check used on
   the API routes, or a simple hardcoded admin password from an env var —
   whichever is less friction for a single-user tool.

## Acceptance criteria

- Visiting `/admin/usage` (with the right auth) shows real numbers pulled
  from `storage_accounts` and the live `checkPoolHealth()` call.
- Uploading a test file and refreshing the page shows the used-space number
  for the backend that received it go up.
