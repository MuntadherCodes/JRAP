# JRAP Deployment Guide

Operations reference for running JRAP outside a dev laptop. The local development
setup lives in the README; this document covers what changes in a real deployment.

## Topology

JRAP is a single Spring Boot application (the `jrap-app` module) plus:

| Component  | Required | Purpose |
|------------|----------|---------|
| PostgreSQL 16 | yes | All tenant data. Row-level security is ACTIVE — see roles below. |
| Object storage (S3/MinIO) | optional | Snapshot payloads (`jrap.snapshots.store=s3`). Filesystem is the default. |
| SMTP relay | optional | Real email (verification, invitations, schedule notifications). Log-only without it. |
| Redis / RabbitMQ | not yet | Reserved in docker-compose for later scaling; the beta app does not use them. |

The frontend (`frontend/`) builds to static files (`npm run build`) served by any
static host or reverse proxy; it talks to the backend at `/api/v1/*`.

## Database roles (do not skip)

Two connections, two roles — this is the tenant-isolation backbone:

- **Flyway migrations** run as the schema owner (`spring.flyway.user`). In dev this is
  the compose superuser; in production create a dedicated migration role.
- **The application** connects as `jrap_app` (`spring.datasource.username`), a
  restricted role subject to row-level security on every tenant table. Never point the
  application datasource at a superuser: RLS does not apply to table owners, and the
  isolation guarantees disappear silently.

## Configuration checklist

Everything is standard Spring configuration (env vars use relaxed binding, e.g.
`JRAP_CRAWL_PAGECAPDEFAULT` for `jrap.crawl.page-cap-default`).

**Identity & auth**

- `jrap.security.jwt-secret` — base64 of a long random value (the checked-in value is
  dev-only; generate your own: `openssl rand -base64 48`). Rotating it invalidates all
  sessions. Access-token TTL: `jrap.security.access-token-ttl` (default PT15M).
- `jrap.admin.emails` (`JRAP_ADMIN_EMAILS`) — comma-separated platform-admin logins.
  Admin rights are config-driven: the JWT `admin` claim is minted at login for these
  addresses only.
- `jrap.app.base-url` — the public frontend URL used in verification/invitation links.

**Crawler & sources**

- `jrap.contact-email` — appears in the crawler User-Agent (CON-2); set a real inbox.
- `jrap.crawl.page-cap-default` — default page cap per audit.
- `jrap.integrations.per-host-min-interval-ms` — politeness floor (1000 = 1 req/s/host,
  the CON-2 baseline; lower only in tests). A robots.txt `Crawl-delay` raises the
  effective per-host interval automatically, capped at 30 s.
- `jrap.integrations.issn-portal-user-agent` (`JRAP_ISSN_PORTAL_UA`) — optional
  browser-profile User-Agent used ONLY for the ISSN Portal, which blocks bots. Leave
  empty to keep the honest JRAP UA; analyst manual evidence (FR-INT-7) covers the gap.

**Snapshot storage**

- Default: `jrap.snapshots.store=filesystem` with `jrap.snapshots.root-dir` on a
  persistent volume.
- S3/MinIO: `jrap.snapshots.store=s3` plus `jrap.snapshots.s3.endpoint`, `.bucket`
  (create it first), `.region`, `.access-key`/`.secret-key`
  (`JRAP_S3_ACCESS_KEY`/`JRAP_S3_SECRET_KEY`). The client is a built-in SigV4
  implementation (no AWS SDK), path-style addressing — MinIO works out of the box.
  Snapshots are content-addressed, so re-runs never duplicate payloads. Write the
  endpoint without an explicit default port (`http://minio` — not `http://minio:80`;
  non-default ports like `:9010` are fine): a redundant `:80`/`:443` makes the signed
  Host header disagree with the one sent, and every request fails signature checks.

**Email**

- Setting `spring.mail.host` (plus port/username/password and the usual
  `spring.mail.properties.mail.smtp.*` flags) switches from the logging adapter to
  real SMTP automatically. Sender address: `jrap.mail.from` (falls back to
  `spring.mail.username`). Delivery is best-effort: failures are logged, workflows
  continue — email is notification, never authorisation.

**AI narrative (optional, CON-4)**

- `jrap.ai.provider=anthropic` + `JRAP_AI_API_KEY` enables the drafted narrative
  section; `disabled` (default) produces the deterministic report only. The report
  guard treats AI text as untrusted either way.

## Background workers

All pollers run inside the application process — no separate worker deployment:

- `jrap.crawl.poll-interval-ms` — audit runner poll (default 5000).
- `jrap.platform.webhook-poll-ms` — webhook dispatcher (default 30000).
- `jrap.platform.schedule-poll-ms` — scheduled re-audits (default 60000).

Run ONE application instance during the beta. The audit runner claims work with
short transactions and resumes safely after a restart (the frontier is the
checkpoint), but the pollers are not yet coordinated across instances.

## Security notes

- Registration endpoint state: self-serve registration can be disabled in
  `SecurityConfig`; invitations are the intended growth path for a closed beta.
- API keys (`jrap_` prefix) are stored as SHA-256 hashes; the secret is shown once at
  creation. Scopes map to roles (read→VIEWER, write→ANALYST) and each key carries its
  own per-minute rate limit.
- Webhook deliveries are signed (`X-JRAP-Signature`, HMAC-SHA256 of the body with the
  endpoint's secret); consumers must verify it.
- Released reports are immutable at the database level (trigger-enforced) and
  SHA-256-stamped; exports carry the hash.
- The platform crawl blocklist (admin console) stops fetches at the crawler, before
  robots.txt — use it for abuse complaints.

## Health & observability

- `GET /actuator/health` — liveness/readiness.
- `GET /actuator/prometheus` — metrics scrape endpoint.
- Security-relevant actions (logins, key usage, review decisions, report releases,
  admin changes) land in the append-only `security_audit` table.

## Upgrade procedure

1. Back up PostgreSQL (snapshots too if on filesystem storage).
2. Deploy the new jar; Flyway applies migrations forward-only at startup as the
   migration role. Never edit an applied migration.
3. Watch `/actuator/health` and the startup log for the Flyway summary.
4. Frontend: rebuild static files from the same tag and swap them after the API is up.
