# Prompt 12 — End-to-end testing + deployment

**Feed this to Claude Code / Antigravity after all previous prompts are
done. This is the "make sure it actually works" pass.**

---

## Context

Everything from Prompts 01–11 is built. Time to verify the whole chain
works together and get the backend deployed somewhere it runs 24/7 (needed
since the web app depends on it being reachable whenever a file is
taken, not just when you happen to be at your dev machine).

## Task

1. Write a manual test checklist (as a markdown file
   `docs/02-test-checklist.md`) covering:
   - Fresh install → permission grants → backfill existing photos →
     verify count in admin dashboard matches device photo count.
   - Take a new photo → verify it appears in the manifest within a few
     minutes without opening the app.
   - Force one pool remote to fail (e.g. temporarily rename it in
     rclone.conf) → verify upload still succeeds via automatic fallback to
     another pool remote or Telegram → restore the remote afterward.
   - Fully exhaust a small-quota remote (or simulate by lowering the
     health-check threshold in `storage-router.js` temporarily) → verify
     `mfs` policy routes around it.
   - Kill the rclone rcd service entirely → verify uploads fall back to
     Telegram cleanly, no crashes, no lost photos.
   - Reinstall the app → verify no duplicate uploads (hash dedupe against
     `/api/backup/manifest` working).
   - Airplane mode a photo capture → reconnect → verify it completes.
2. Deployment: help me deploy `` to wherever I'm already hosting
   Bhandar (ask me for the target — VPS, Vercel, Railway, etc. — if it's
   not obvious from existing Bhandar deployment config in this workspace).
   Note: Vercel's serverless functions won't work well here since
   `rclone rcd` needs to run as a persistent background process — a VPS or
   any host supporting long-running Docker containers is required.
3. Set up basic uptime monitoring — even something as simple as a cron job
   hitting `/api/backup/usage` every 15 minutes and alerting (e.g. via a
   Telegram message through the existing bot) if it fails, since a silent
   backend outage means silently failing backups.
4. Document the full credential list (which env vars, which rclone remotes)
   somewhere I can find it 6 months from now — `docs/03-credentials-index.md`
   listing which provider each credential belongs to and where to
   regenerate it if lost (NOT the actual credential values — just an index
   of what exists and where).

## Acceptance criteria

- Every item in the manual test checklist passes.
- App is reachable 24/7 from the browser on both Wi-Fi and mobile
  data.
- A simulated backend outage triggers an alert within 15 minutes.
- `docs/03-credentials-index.md` exists and has no actual secrets in it.
