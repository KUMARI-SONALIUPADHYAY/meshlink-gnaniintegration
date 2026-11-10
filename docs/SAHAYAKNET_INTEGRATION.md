# SahayakNet capabilities integrated into MeshLink

## Product boundary
MeshLink remains the Android offline-first field application and BLE resilience layer. SahayakNet is the wider disaster-intelligence and coordination ecosystem described in `SAHAYAKNET_Final_Team_Report`. This integration brings the citizen/field reporting workflow into MeshLink and provides backend ingestion for a future authorized command center. It does not claim to implement all external channels or AI vision modules.

## Android Response workspace
The primary Assistant destination is now **Response**. It supports four structured report types:

- `INCIDENT`: incident category, severity and description, with optional population and resource counts.
- `PEOPLE`: total affected people and optional children, elderly, injured, disability-support and pregnancy counts.
- `RESOURCES`: requested food kits, water litres, first-aid kits, blankets and shelter spaces.
- `VOLUNTEER`: volunteers available and their skills/vehicles/supplies description.
- `SAFE_ZONE`: a reported shelter/safe-zone name, capacity, accessibility notes and open/closed report; it is not marked verified automatically.

A report is broadcast to the current MeshLink group using the existing mesh text transport. If the user explicitly checks cloud-sync consent, the same structured payload is staged in the durable cloud outbox as `COORDINATION_UPDATE`. Cloud sync remains independent from local BLE transport. The screen differentiates authenticated peers from internet availability and avoids claiming delivery merely because submission was tapped.

The previous Gnani workflow is retained under **More → Gnani voice processing**. The next integration step is to pass Prisma transcripts into the Response workspace, use Evon for schema-constrained extraction, and require sender review before submission. No automatic medical diagnosis or dispatch is permitted.

## Backend

- `CoordinationRecord` stores idempotent, account-owned structured coordination records as JSONB.
- `POST /api/sync/events` now persists `COORDINATION_UPDATE` records instead of only recording a receipt.
- `GET /api/coordination/records` returns the caller's records; responders/admins can view all records.
- `GET /api/coordination/summary` is restricted to responders/admins and returns report sums and resource-demand sums.
- Summary explicitly warns that report sums may overlap and are not deduplicated population estimates.

Apply the new Prisma migration and regenerate the Prisma client before deploying the backend:

```bash
cd backend
npm ci
npx prisma generate
npx prisma migrate deploy
npm start
```

## Not represented as completed

- No official NDMA/state/district control-room integration is configured.
- SMS, WhatsApp, telephony/IVR and missed-call intake are not implemented in this Android revision.
- CCTV/drone/RTSP Vision AI, population baselines, telecom aggregate signals, route planning and automatic resource allocation are not implemented here.
- The current Android Response workspace does not yet capture GPS coordinates or evidence attachments on these new record types; use the existing Incident Reports flow for its current GPS-consent and photo-upload support.
- AI-generated severity, counts and resource estimates require human review. Reports are not verified simply because they are stored or synchronized.
- Mesh delivery is best-effort and depends on working authenticated BLE peers, range, battery and acknowledgements.
