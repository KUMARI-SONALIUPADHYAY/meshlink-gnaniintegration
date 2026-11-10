# MeshLink Cloud API

This is the first real cloud transport for MeshLink 2.0. It is a Node.js 20+, Express and PostgreSQL service using Prisma ORM. The Android app communicates only with this API over HTTPS; no database password, JWT signing key or provider credential is embedded in the app.

## Architecture

- PostgreSQL stores accounts, roles, incidents, idempotency receipts, disputes and media metadata.
- Express validates every input with Zod, hashes passwords with bcrypt, issues 30-minute JWT access tokens, rate-limits authentication/API routes and enforces responder/admin role checks.
- The Android client stores its short-lived access token encrypted with an AES-GCM key in Android Keystore. Pending outbox events are bound to the current account when available, or to the first account that synchronizes a report queued before sign-in; signing into a different account will not silently upload an already-bound report.
- Incident reports are first saved in Room's durable outbox. A stable event ID makes retries idempotent. The server acknowledges accepted event IDs; only acknowledged records become SYNCED locally.
- Outbox retries use a 30-second minimum retry delay. Upload IDs are written back to the outbox before event submission so a network interruption does not create repeated evidence uploads.
- Image uploads are private, authenticated, limited to 5 MB and JPEG/PNG/WebP. The server verifies image signatures, stores random filenames outside public web roots and authorizes retrieval.
- Community accounts can submit reports and disputes. Only separately provisioned RESPONDER/ADMIN accounts can change incident status or verification.

## Local server setup

Requirements: Node.js 20+, PostgreSQL 16+, and an HTTPS reverse proxy for any non-loopback deployment.

1. Copy `.env.example` to `.env` and set `DATABASE_URL`, a strong `JWT_SECRET` (at least 32 random characters), `PORT`, and `UPLOAD_DIR`.
2. Create a PostgreSQL database and database user with least-privilege access.
3. Install dependencies (this repository intentionally uses `npm install`; do not use `npm ci` until a `package-lock.json` has been generated and committed):

   ```powershell
   npm install
   npm run db:generate
   ```

4. Apply the checked-in Prisma 6 migrations to your configured PostgreSQL database:

   ```powershell
   npm run db:migrate
   ```

   The `package.json` pins the Prisma CLI to the Prisma 6 line. Run the scripts above only after `npm install` completes successfully. Do not use an unpinned `npx prisma` command before installing dependencies; it may download a different major version.

5. Start the API for local development:

   ```powershell
   npm run dev
   ```

5. Check `GET /health`.

For a server deployment, use the included Docker Compose file with a private PostgreSQL volume. Put the API behind a TLS reverse proxy/load balancer; do not expose port 8080 directly to the public internet. Set `TRUST_PROXY=1` only when exactly one trusted reverse proxy sits in front of Express.

## Docker deployment

From this directory:

```bash
cp .env.example .env
# Edit .env: set POSTGRES_PASSWORD and JWT_SECRET (openssl rand -hex 32)
docker compose up -d --build
# Run migrations from the API container
docker compose exec api npm run db:migrate
```

The sample compose file binds the API to `127.0.0.1:8080` only. Configure the host TLS reverse proxy using `deploy/nginx.conf.example` to forward to `127.0.0.1:8080`. Do not change the bind to `0.0.0.0` for a public server. Back up the Postgres and uploads volumes together. For horizontally scaled deployments, replace local uploads with private object storage and implement signed, short-lived access URLs before adding multiple API replicas.

## Responder accounts

Public registration always creates the `COMMUNITY` role. A client cannot request responder/admin privileges. Provision responders through an operator-controlled server shell:

```bash
read -s PASSWORD
printf '%s' "$PASSWORD" | npm run admin:create -- responder@example.org "Responder Name" RESPONDER
unset PASSWORD
```

Only trusted operators should run this command. Do not put responder credentials into Android source or app configuration. The role is reloaded from PostgreSQL for every authenticated request.

## Android configuration

In the project-root `local.properties`, preserve `sdk.dir` and add:

```properties
cloud.api.base.url=https://YOUR_PUBLIC_API_HOST
```

Use an HTTPS URL with no trailing slash. The app intentionally rejects HTTP URLs. For an emulator-only local API, configure a TLS development proxy and use its HTTPS hostname; do not weaken the app's HTTPS check for production.

## API summary

- `POST /api/auth/register` — community registration only.
- `POST /api/auth/login` — email/password; returns a 30-minute bearer token.
- `GET /api/me`, `PATCH /api/me`, `DELETE /api/me` — profile, rename, and password-confirmed account anonymization/deletion.
- `POST /api/media`, `GET /api/media/:id` — private image upload/retrieval.
- `POST /api/incidents`, `GET /api/incidents`, `GET /api/incidents/:id` — incident reporting and dashboard.
- `PATCH /api/incidents/:id/status`, `PATCH /api/incidents/:id/verification` — responder/admin only, optimistic version check.
- `POST /api/incidents/:id/dispute` — authenticated community dispute.
- `POST /api/sync/events` — authenticated idempotent offline outbox ingestion.

Incident status transitions are constrained server-side. Status changes and verification changes increment a version; stale updates return HTTP 409. Reports start UNVERIFIED. AI-generated assessments are not used to set VERIFIED.

## Security and operational limitations

- Deploy behind HTTPS/TLS. JWT_SECRET and DATABASE_URL stay only on the server.
- Access tokens expire after 30 minutes. There is no refresh-token endpoint yet; users sign in again after expiry.
- Registration does not yet include email verification, MFA, password reset or account recovery. Do not use this as a public emergency dispatch identity system until those controls and operational procedures are added.
- Cloud incidents are visible to authenticated community users; location is returned only when the reporter explicitly consented to share it. Do not submit another person's precise location without their permission.
- Account deletion anonymizes the reporter identity, removes their uploaded media and disputes, and retains incident reports for safety/audit integrity. A retention script purges resolved incidents older than 365 days and sync receipts older than 400 days. Schedule `npm run retention:run` monthly after reviewing local legal/retention requirements.
- This backend is not connected to police, ambulance, fire services or an official dispatch network. Dashboard status is community coordination only.
- Configure database backups, monitoring, secret rotation, TLS certificates, rate-limit monitoring, and incident-response procedures before public launch.
