# MeshLink 2.0 — Integrated application map

## Primary navigation

The seven primary sections are implemented in the main Compose navigation:

1. **Home** — current Online/Offline mode, internet reachability, BLE service state, authenticated peer count, active peer conversations, pending cloud-sync count, recent high-priority/SOS messages, and quick actions. Counts are derived from Room/mesh/cloud state; no sample values are inserted.
2. **Chats** — existing personal and group conversations, text and voice messages, timestamps, delivery acknowledgements and existing persistent queue behavior.
3. **Nearby** — offline map/mesh topology and a list of currently authenticated BLE peers. Coordinates are shown only when actual GPS fixes have been shared.
4. **SOS** — emergency category and severity selection, optional GPS sharing, optional voice attachment and explicit send confirmation. SOS packet format remains compatible; category/severity are carried in the existing message text field. Local alarm begins immediately and GPS lookup is bounded to 1.2 seconds.
5. **AI Voice** — existing Prisma → Evon → Translation → Timbre screen, gated by Online Mode, validated internet and per-provider configuration.
6. **Reports** — authenticated cloud incident reporting, incident status/verification, disputes, media evidence and sync controls.
7. **Profile** — local identity, mesh toggle, mode selection, cloud account, privacy notes, activity log and safety review.

The navigation selection and nested Profile section use saveable Compose state so the current section survives ordinary Activity recreation.

## State and ownership

- `MeshEngine` remains the single owner of BLE discovery, GATT, authenticated peers, message routing, relay, store-and-forward, local SOS, voice transfer and Room-backed mesh state.
- `MeshDatabase` remains the local source of truth for messages, events, voice assets, AI outputs and cloud outbox/cache records.
- `CloudSyncCoordinator` and `CloudSyncWorker` own remote synchronization. They do not participate in local BLE transport or SOS delivery.
- Prisma, Evon, Translation and Timbre remain separate provider clients. Provider availability is explicit; an unconfigured service is not represented as operational.
- `MainActivity` coordinates navigation and UI callbacks. It does not replace the underlying mesh or provider clients.

## Emergency privacy

SOS location is opt-in in the SOS screen. When enabled, location lookup has a short timeout and is omitted when unavailable. The local alarm is started before location lookup or BLE retries. Cloud SOS summary sharing remains a separate preference and is staged only after local SOS packet creation.

Community incident media and location sharing remain explicit opt-ins. Local mesh messages and voice notes are not uploaded automatically.

## Verification boundary

Static Kotlin parser checks, XML parsing, source-level call-site checks and ZIP integrity checks are performed during implementation. A complete Android Gradle build, instrumentation tests, live provider calls, BLE multi-device validation and deployed-backend verification require the Android SDK, Gradle distribution, configured external services and physical devices. Those are not claimed as complete here.
