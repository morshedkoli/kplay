# Deploying kPlay to a VPS

Docker Compose runs the app on `127.0.0.1:3000`; nginx on the host terminates
TLS and proxies to it. MongoDB stays on Atlas.

## Why a VPS and not Vercel

The two heavy routes don't fit a serverless platform:

- `POST /api/media/upload` streams a whole movie file to Google Drive. Vercel
  caps request bodies at 100 MB.
- `GET /api/media/stream/[id]` proxies file bytes for the whole playback. Vercel
  caps function duration at 300s and bills every byte as bandwidth.

On a VPS both are just long-lived HTTP connections, which is what the nginx
config below is tuned for.

## Wiping a VPS that ran something else

Only do this on a box whose current contents you are willing to lose — the
steps below destroy every container, image, and volume on the host, including
any database that lived in a Docker volume. Take backups first.

```bash
docker ps -a
```

```bash
docker compose -f /path/to/old/docker-compose.yml down -v
```

Then remove everything Docker still holds:

```bash
docker system prune -a --volumes -f
```

Disable the old nginx sites and reload, so the freed port and hostname stop
being served:

```bash
ls -l /etc/nginx/sites-enabled/
```

```bash
sudo rm -f /etc/nginx/sites-enabled/<old-site> && sudo nginx -t && sudo systemctl reload nginx
```

Certbot renews certificates for domains that no longer resolve here and will
start emailing about failures. Drop the ones you have retired:

```bash
sudo certbot delete --cert-name <old-domain>
```

Finally remove the old checkout and its secrets:

```bash
sudo rm -rf /srv/<old-app>
```

## One-time VPS setup

1. Install Docker Engine, the Compose plugin, and nginx.
2. Point the DNS A record at the VPS.
3. Add the VPS's public IP to the Atlas **Network Access** allowlist. Without
   this the app starts fine and then every query times out.
4. Publish the Google OAuth app (move it out of "Testing"), otherwise the
   refresh token expires after 7 days and streaming dies with no obvious cause.

## Deploy

```bash
git clone <repo> /srv/kdrive && cd /srv/kdrive
```

```bash
cp .env.production.example .env.production && chmod 600 .env.production
```

Fill in `.env.production`, then:

```bash
docker compose up -d --build
```

Create the MongoDB indexes once (safe to re-run; it's idempotent):

```bash
docker compose run --rm app node lib/migrations/001-indexes.js
```

## nginx + TLS

```bash
sudo cp deploy/nginx/kdrive.conf /etc/nginx/sites-available/kdrive
```

Enable it and reload:

```bash
sudo ln -sf /etc/nginx/sites-available/kdrive /etc/nginx/sites-enabled/kdrive && sudo nginx -t && sudo systemctl reload nginx
```

```bash
sudo certbot --nginx -d murshedkoli.me
```

## Updating

```bash
git pull && docker compose up -d --build
```

Old images pile up over time; reclaim the space with:

```bash
docker image prune -f
```

## Checks

```bash
curl -fsS https://murshedkoli.me/api/health
```

```bash
docker compose ps && docker compose logs -f --tail=100 app
```

## Firewall

Only 80, 443, and SSH should be open. The app port is published to `127.0.0.1`
only in `docker-compose.yml`, so it is not reachable from outside even if the
firewall is permissive — do not change that binding to `0.0.0.0`.

## Notes on sizing

- The stream route holds one Drive connection per active playback. Memory stays
  flat (bytes are piped, never buffered) but each stream uses upstream
  bandwidth equal to the playback bitrate, so the VPS's network cap, not its
  RAM, is the limit on concurrent viewers.
- Uploads are piped straight through with `proxy_request_buffering off`, so
  disk usage doesn't grow with upload size.
- The image is ~85 MB of `node_modules`, most of it `googleapis`. If that ever
  matters, importing `googleapis/build/src/apis/drive` directly instead of the
  umbrella package would cut the bulk of it.
