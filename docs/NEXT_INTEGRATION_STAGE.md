# Next integration stage — cloud synchronization and emergency coordination

Stage 8 now has a durable local outbox (`cloud_sync_outbox`) and explicit local mesh sharing of reviewed emergency summaries. It deliberately does not upload data: no cloud provider or remote API contract has been selected.

## Required decision before remote sync

Choose one of:

1. Firebase Authentication + Firestore + Cloud Storage, with a server-side function/proxy for authorization and media handling; or
2. A project-owned HTTPS coordination backend with documented authentication, event schema, upload/acknowledgement, and retention contract.

Before implementing a remote transport, define user consent for each data category (text summary, precise location, voice audio), device/user identity, authorization, encryption, retention/deletion, idempotency, conflict resolution, and server-side validation. Do not embed service-account private keys or privileged server secrets in the Android app.

## Current local coordination behavior

- A user can review the Evon report and explicitly share the advisory summary with nearby MeshLink peers over the existing local BLE mesh.
- The action is a normal broadcast and does not send SOS or claim responders were dispatched.
- CloudSyncCoordinator can stage a consent-granted bounded event in Room, but it does not upload until a real transport is implemented and configured.
- BLE messaging, relay, voice-note transfer, and SOS remain independent of cloud availability.
