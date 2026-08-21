# Prompt 02 — Union remote + rclone rcd background service

**Feed this to Claude Code after Prompt 01 is complete.**

---

## Context

I have individual rclone remotes configured (see `rclone listremotes`).
Now I need them merged into a single "pool" remote and exposed as a local
HTTP API my Next.js backend can call. Reference: `docs/01-architecture.md`.

## Task

1. Create the union remote combining every remote I successfully configured
   in Prompt 01 (use whichever subset actually exists — check
   `rclone listremotes` first, don't assume all 11):
   ```
   rclone config create pool union upstreams="r2: b2: storj: idrivee2: oracle: filebase: mega: gdrive: onedrive: dropbox: pcloud:" policy=mfs
   ```
   Adjust the `upstreams` list to only the remotes that actually exist.
2. Test it: `rclone copy test.txt pool:kdrive-test/` then
   `rclone ls pool:kdrive-test/` — confirm the file landed on
   whichever remote had the most free space.
3. Create `rclone/docker-compose.yml` running `rclone/rclone` image
   with:
   - `rclone rcd --rc-addr=0.0.0.0:5572 --rc-user=${RCLONE_RC_USER} --rc-pass=${RCLONE_RC_PASS}`
   - The rclone.conf file mounted read-only as a volume
   - Only bound to `127.0.0.1:5572` on the host, never exposed publicly
4. Create `rclone/README.md` documenting: how to start/stop the
   service, how to add a new remote later, how to check pool usage via
   `curl -u user:pass http://127.0.0.1:5572/operations/about -d '{"fs":"pool:"}'`.
5. Add `RCLONE_RC_URL`, `RCLONE_RC_USER`, `RCLONE_RC_PASS` to
   `.env.example` (values as placeholders, not real credentials).

## Acceptance criteria

- `docker compose up -d` in `rclone/` starts the rcd service.
- `curl -u $RCLONE_RC_USER:$RCLONE_RC_PASS http://127.0.0.1:5572/operations/about -d '{"fs":"pool:"}'`
  returns valid JSON with free/used space.
- Port 5572 is not exposed outside localhost/the docker network.
- `.env.example` updated.
