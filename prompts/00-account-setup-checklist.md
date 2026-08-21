# 00 — Account setup checklist (DO THIS YOURSELF, not an AI agent prompt)

This step can't be automated — it's OAuth logins and account creation in
your browser. Budget about an evening. Check each off as you go; you don't
need all of them before starting the build (Prompt 01 onward works with
whatever subset you've configured).

## S3-compatible (fast to set up — API keys only)

- [ ] **Cloudflare R2** — dashboard.cloudflare.com → R2 → create bucket
      `kdrive` + `kdrive-thumbnails` → create API token (Object
      Read & Write). Save: Account ID, Access Key ID, Secret Access Key.
- [ ] **Backblaze B2** — backblaze.com → create bucket → App Keys → create
      key scoped to that bucket. Save: keyID, applicationKey, bucket
      endpoint.
- [ ] **Storj** — storj.io → create project → create bucket → Access →
      create S3 credentials. Save: Access Key, Secret Key, endpoint
      (gateway.storjshare.io).
- [ ] **IDrive e2** — idrivee2.com → create bucket → Access Keys. Save:
      Access Key, Secret Key, endpoint.
- [ ] **Oracle Cloud (Always Free)** — cloud.oracle.com → sign up (requires
      card for verification but Always Free resources never charge) →
      Object Storage → create bucket → Customer Secret Keys (under your
      user profile) for S3-compatible access. Save: Access Key, Secret Key,
      namespace, region.
- [ ] **Filebase** — filebase.com → create bucket → Access Keys. Save:
      Access Key, Secret Key.

## OAuth-based (rclone will walk you through the browser login)

These don't need manual key-gathering — Prompt 02 runs `rclone config` which
opens a browser for you to log in. Just make sure you have accounts:

- [ ] Google account (for Google Drive — check it isn't already near its
      15GB limit from Gmail/Photos)
- [ ] Microsoft account (OneDrive)
- [ ] Dropbox account
- [ ] pCloud account
- [ ] MEGA account (email/password, no OAuth)

## Already have (from Bhandar)

- [ ] Telegram API ID + API Hash (my.telegram.org)
- [ ] Existing GramJS session string
- [ ] MongoDB connection string

## Skipped by default (see PRD open question #4)

- Scaleway (75GB free) — requires ID verification. Add later if you decide
  the extra ~75GB is worth it.

---
Once you've got at least the S3-compatible ones + MEGA + one OAuth provider,
move to `prompts/01-rclone-remotes-setup.md`. You don't need all 11 before
your first successful upload.
