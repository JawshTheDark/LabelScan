# LabelScan backend (PocketBase + MinIO)

Persists your catalog and scan images off-device so they survive app reinstalls and
new phones. PocketBase gives a REST API, auth, a SQL store (SQLite) and an admin web
UI; the images live in MinIO (S3), which scales to as many photos as your disk holds.

```
phone  ──HTTPS──▶  PocketBase (REST API + auth + SQLite metadata)
                        └── file storage ──▶  MinIO (S3 bucket: images)
```

## 1. Deploy on irc.so

```sh
ssh user@irc.so
mkdir -p /storage/docker/labelscan
cd /storage/docker/labelscan
# copy this server/ directory's contents here (docker-compose.yml, .env.example, pb_migrations/)
cp .env.example .env
# edit .env: set a long random MINIO_ROOT_PASSWORD
docker compose up -d
docker compose logs -f pocketbase   # watch it create the collections on first boot
```

This starts three things: MinIO (image storage), a one-shot job that creates the
`labelscan` bucket, and PocketBase (API on `127.0.0.1:8090`). Ports bind to localhost
only — your reverse proxy terminates TLS in front.

## 2. Put it behind HTTPS

Android blocks plaintext HTTP by default, so the app needs an `https://` URL. Point a
hostname (e.g. `labelscan.irc.so`) at PocketBase through whatever proxy you already run:

**Caddy**

```
labelscan.irc.so {
    reverse_proxy 127.0.0.1:8090
}
```

**Traefik / nginx** — proxy the host to `127.0.0.1:8090`, standard websocket-friendly
config (PocketBase uses SSE for realtime; a normal reverse_proxy handles it).

## 3. First-run setup (once, in the admin UI)

Open `https://labelscan.irc.so/_/` and create the admin account when prompted.

**a. Point file storage at MinIO** — Settings → Files storage → enable **S3 storage**:

| Field | Value |
|---|---|
| Endpoint | `http://minio:9000` |
| Bucket | `labelscan` |
| Region | `us-east-1` |
| Access key | your `MINIO_ROOT_USER` |
| Secret | your `MINIO_ROOT_PASSWORD` |
| Force path-style | **on** |

Save, then use "Test S3 connection". (Leave it off and images just live on the
PocketBase volume instead — MinIO is the scalable choice.)

**b. Create the app's login** — Collections → `users` → New record. Give it an email and
password. That's what you'll type into the app's Sync settings. (The `products` and
`photos` collections were created automatically by the migration.)

## 4. Connect the app

In LabelScan: **⋮ → Sync settings** → enter
`https://labelscan.irc.so`, the app user's email + password → **Test & sign in** →
turn on **Auto-sync**. Then **Back up now** pushes everything you already have.

On a fresh install, sign in and tap **Restore** to pull the catalog back; images
download on demand as you open items.

## Storage sizing

Stored images are capped at 1600px, JPEG q85 ≈ **0.3 MB each**. Metadata is a few KB per
UPC. So:

| Catalog | Photos/item | Disk |
|---|---|---|
| 5,000 UPCs | 2 | ~3.5 GB |
| 20,000 UPCs | 3 | ~21 GB |
| 50,000 UPCs | 3 | ~52 GB |

MinIO shows usage in its console (`127.0.0.1:9001`, tunnel over SSH). Watch bucket size
there.

## Backups

Everything that matters is two directories: `pb_data/` (the SQLite DB) and
`minio-data/` (the images). Snapshot or `rsync` those and you can restore the whole
server. `.env`, `pb_data/`, `pb_public/` and `minio-data/` are git-ignored.
