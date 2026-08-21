# Prompt 01 — Configure individual rclone remotes

**Feed this to Claude Code (it can run shell commands and edit config for
you). Have your credentials from `00-account-setup-checklist.md` ready —
you'll need to paste keys when prompted and complete OAuth logins in a
browser for Drive/OneDrive/Dropbox/pCloud.**

---

## Context

I'm building KDrive, a multi-backend free storage aggregator. I need
`rclone` installed and configured with one remote per provider before I can
create the union pool. Full architecture is in `docs/01-architecture.md`.

## Task

1. Install rclone if not already present (`curl https://rclone.org/install.sh | sudo bash`).
2. For each S3-compatible provider I give you credentials for, create an
   rclone remote using `rclone config create <name> s3` with the
   appropriate `provider`, `endpoint`, `access_key_id`, `secret_access_key`
   flags. Use these exact remote names so later prompts match:
   - `r2` (provider=Cloudflare, endpoint=`https://<account_id>.r2.cloudflarestorage.com`)
   - `b2` (provider=Other or use rclone's native `b2` backend type instead
     of `s3` — b2 backend is more efficient for Backblaze specifically)
   - `storj` (provider=Other, endpoint=`https://gateway.storjshare.io`)
   - `idrivee2` (provider=IDrive, endpoint from IDrive e2 dashboard)
   - `oracle` (provider=Other, endpoint=`https://<namespace>.compat.objectstorage.<region>.oraclecloud.com`)
   - `filebase` (provider=Other, endpoint=`https://s3.filebase.com`)
3. For MEGA: `rclone config create mega mega user=<email> pass=<obscured password via rclone obscure>`.
4. For OAuth providers, walk me through `rclone config create gdrive drive`,
   `rclone config create onedrive onedrive`, `rclone config create dropbox dropbox`,
   `rclone config create pcloud pcloud` — each will print a URL or open a
   browser for login. Pause and wait for me to confirm each login completed
   before moving to the next.
5. After each remote is created, verify it works: create a small test file
   and run `rclone copy test.txt <remote>:kdrive-test/` then
   `rclone ls <remote>:kdrive-test/` to confirm.
6. Save the final rclone config file location and remind me to back it up
   securely (it contains all my credentials) — do NOT commit it to git.
   Add `rclone.conf` to `.gitignore` in the repo root.

## Acceptance criteria

- `rclone listremotes` shows all configured remotes.
- Each remote passes the test-file copy + list check.
- `rclone.conf` is gitignored.
- I have a written note of which providers I actually completed (some may
  be skipped per the checklist) — output this as a summary at the end.
