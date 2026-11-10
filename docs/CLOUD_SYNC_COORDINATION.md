# MeshLink Cloud Synchronization and Emergency Coordination

## Stage 9 implementation

Backend selected after inspection: no Firebase project, google-services configuration, Firebase SDK or Firebase authentication was present in the Stage 8 Android source. Rather than introduce an unconfigured Firebase dependency, this stage adds a project-owned Node.js 20 / Express / PostgreSQL API under `backend/`.

### Android

- `CloudApiClient.kt`: HTTPS-only API client, community login/registration, profile, incident listing, status/verification/dispute calls, private image upload and sync-event ingestion.
- `EncryptedCloudTokenStore.kt`: AES-GCM token encryption using a non-exportable Android Keystore key.
- `CloudSyncCoordinator.kt`: consent-gated Room outbox, deterministic event IDs, serialized sync, 30-second retry delay, account binding, stable timestamps and media-upload ID persistence.
- `CloudSyncWorker.kt` / `CloudSyncScheduler.kt`: WorkManager network-constrained retry that survives Activity destruction and process restarts; the worker checks the persisted Online/Offline mode before uploading.
- `CloudIncidentEntity.kt`: cached cloud incidents for offline dashboard viewing.
- `CloudDashboard.kt`: login/register, community report form, optional image and consented GPS, nearby filter, incident list, map-app deep links, status/verification controls for responders, dispute control for community accounts and sync state.
- `MeshEngine.sendSos` returns the actual SOS packet message ID. SOS transmission remains first and local; cloud staging occurs only after the mesh send returns and only if the user opted into future SOS summary synchronization.
- Room version 6 migration adds outbox retry metadata and cloud incident cache. Destructive fallback migration was removed so supported migrations do not silently erase local emergency records.

### Cloud event flow

1. User explicitly consents to share a community report or enables future SOS summary sync.
2. The app writes the event to Room with a deterministic event ID derived from event type and original message ID.
3. When Online Mode, validated internet and an authenticated account are available, WorkManager wakes the coordinator; the Activity can also request an immediate sync. The coordinator serializes uploads per process.
4. Optional media is uploaded first. Its returned private media ID is persisted into the outbox before event submission.
5. The API writes a unique sync receipt and incident in one database transaction. Repeated event IDs are acknowledged as duplicates, not recreated.
6. Only acknowledged events become SYNCED locally. Failures retain their payload, timestamps, original message ID and retry metadata.

### Backend

- `backend/src/server.js`: auth, validation, role authorization, incidents, disputes, media and sync API.
- `backend/prisma/schema.prisma`: PostgreSQL data model.
- `backend/Dockerfile`, `backend/docker-compose.yml`: container deployment with Postgres and private media volume.
- `backend/scripts/create-user.js`: operator-only responder/admin provisioning.
- `backend/scripts/retention.js`: resolved-incident and sync-receipt retention task.

## Roles and trust

Public registration always creates COMMUNITY. Only operators provision RESPONDER or ADMIN. Community users may submit reports and disputes. Only responders/admins may advance incident status or set verification. Reports begin UNVERIFIED; an AI-generated assessment never makes a report verified. Status writes use optimistic version checks.

## Privacy

Location coordinates are included only when the report form's location-sharing checkbox is selected and a location is available. Media is optional, private and limited to JPEG/PNG/WebP under 5 MB. Cloud uploads require an explicit report-sharing checkbox. Future SOS cloud summaries have a separate opt-in setting. Local BLE and SOS do not require a cloud account or connection.

## Limitations to complete before public deployment

- Deploy the backend to a managed host or server with PostgreSQL and HTTPS; no live host is bundled.
- Configure `cloud.api.base.url` in root `local.properties`.
- Configure database backups, TLS, monitoring, secret rotation and scheduled retention.
- Add email verification, MFA, password reset, refresh tokens and responder identity verification.
- Map display currently opens coordinates in an installed map app; no in-app map SDK/API key is configured.
- Image files are on the backend's private persistent volume; multi-replica deployments need private object storage.
- No official emergency-dispatch integration exists. Cloud incident states represent community coordination only.
