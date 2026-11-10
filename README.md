# MeshLink — Offline Bluetooth Mesh Emergency Communication

Native Kotlin + Jetpack Compose Android prototype. The offline path uses only BLE, Room/SQLite, Android Keystore, local GNSS and local audio.

## Build
Open this directory in Android Studio with Android SDK Platform 37 and a Gradle-compatible JDK 21. The included wrapper is pinned to Gradle 9.6.0; AGP is 9.4.1 and Kotlin is 2.4.20. From PowerShell run `.\gradlew.bat clean testDebugUnitTest assembleDebug`. The debug APK, if the build succeeds, is `app\build\outputs\apk\debug\app-debug.apk`. See [`docs/FINAL_VERIFICATION_AND_SUBMISSION.md`](docs/FINAL_VERIFICATION_AND_SUBMISSION.md) for setup and environment limitations.

## Offline requirement
For the physical demo, disable Wi-Fi and mobile data on every device. Internet is not required for mesh discovery, GATT transport, routing, persistence, SOS, location or voice transfer.

## Android permissions
BLE permissions are requested for mesh mode. Location is requested for GPS features, microphone for voice, and notifications on supported Android versions.

## Physical validation
Follow `docs/testing.md`. BLE behavior must be verified on real Android hardware.


## 2.6 Reliability updates
- Profile name is MeshLink identity metadata carried by authenticated HELLO, not the handset Bluetooth name.
- Profile avatar is local UI metadata.
- Voice assets are stored per message ID and playback is independent per asset.
- SOS/voice ordering is tolerant: if the voice completes before SOS metadata, the SOS card resolves the stored voice asset when it arrives.
- Offline path remains BLE-only; no simulated nodes, coordinates, distances, or cloud relay were added.


## Gnani voice integration

See [docs/GNANI_CONFIGURATION.md](docs/GNANI_CONFIGURATION.md) for local API-key setup, API behavior, and security limitations.


### Gnani voice workflow

MeshLink integrates Prisma v2.5 speech-to-text, the optional configured Evon inference endpoint, and Timbre v2.5 text-to-speech. See [`docs/GNANI_CONFIGURATION.md`](docs/GNANI_CONFIGURATION.md) for local credentials, supported voice/language pairs, and API behavior.

## Multilingual AI and cloud coordination

- `docs/MULTILINGUAL_WORKFLOW.md` documents Prisma → Evon → Google Cloud Translation → Timbre configuration and supported language boundaries.
- `docs/CLOUD_SYNC_COORDINATION.md` documents the local consent-gated sync outbox and the remaining backend integration requirements.

## Secure cloud coordination backend

A project-owned Node.js/Express + PostgreSQL backend and Android cloud dashboard are included under [`backend/`](backend/). Deployment, authentication, responder provisioning, HTTPS, privacy, sync retry and retention instructions are in [`backend/README.md`](backend/README.md) and [`docs/CLOUD_SYNC_COORDINATION.md`](docs/CLOUD_SYNC_COORDINATION.md). The backend is source code only; it is not deployed or reachable until you configure a host, PostgreSQL and HTTPS URL.

## Integrated application navigation

The current primary navigation is Home, Chats, Nearby, SOS, AI Voice, Reports and Profile. Existing Events and Safety review are available inside Profile. See [`docs/INTEGRATED_APPLICATION.md`](docs/INTEGRATED_APPLICATION.md) for screen responsibilities, state ownership, SOS privacy behavior and verification boundaries.


## Final verification and challenge submission

- [`docs/FINAL_VERIFICATION_AND_SUBMISSION.md`](docs/FINAL_VERIFICATION_AND_SUBMISSION.md) — actual checks, build requirements, known limitations and verification matrix.
- [`docs/API_CONFIGURATION.md`](docs/API_CONFIGURATION.md) — local provider/backend configuration.
- [`docs/DEVICE_TEST_CHECKLIST.md`](docs/DEVICE_TEST_CHECKLIST.md) — physical two-/three-phone and online-service checklist.
- [`docs/CHALLENGE_SUBMISSION.md`](docs/CHALLENGE_SUBMISSION.md) — concise challenge description and safe claims.
- [`docs/DEMO_RECORDING_SCRIPT.md`](docs/DEMO_RECORDING_SCRIPT.md) — demo recording sequence.

The final build and real-device/provider tests are not claimed as complete until executed in the required environment.

## Current UI, BLE and AI integration

The primary navigation is Home, Chats, Nearby and Assistant. SOS remains available from the persistent emergency action; Incident Reports and Profile/Settings are available from the top-bar menu. Nearby distinguishes observed BLE beacons from authenticated connected peers. Online and offline dashboards keep internet state separate from the BLE transport state.

The existing BLE protocol and AI service clients were preserved. BLE lifecycle handling now retries the local server/advertiser/scanner after Bluetooth is re-enabled and cleans up references when the mesh service stops. The Assistant screen supports original-audio preview, duration/file information, editable transcript review, retrying transcription, and copy/share actions for results. Received SOS alerts are surfaced on Home with review/acknowledgement controls and a map action only when real coordinates were shared. Queued text/SOS/alert/location packets can be manually retried using their original message IDs.

See `docs/UI_UX_BLE_AI_IMPLEMENTATION.md` for the older-project comparison, source changes, service boundaries and verification limitations. Physical BLE, live provider and cloud deployment checks remain required before claiming those workflows are device-verified.


## Important: enabling the nearby mesh

The `Mesh` switch starts or stops the MeshLink foreground service. It does **not** turn on the phone's Bluetooth radio. Android requires the user to enable Bluetooth separately. Keep Bluetooth enabled on every participating phone, grant Nearby Devices permissions, and leave MeshLink's Mesh switch on. Internet connectivity is not required for BLE discovery or local mesh messaging. The Home screen offers an Android Bluetooth-enable prompt when it detects that Bluetooth is off.

A nearby beacon is not yet a secure connection: `Discovered` counts advertisements, while `Connected` counts peers that complete the authenticated MeshLink handshake. A message marked `QUEUED` has not been delivered; delivery requires an acknowledgement.

## Assistant usage

The Assistant includes an offline emergency quick guide and fixed English/Hindi emergency phrases that do not require API credentials. For voice transcription, AI analysis, cloud translation, and generated speech, configure the corresponding providers as described in `docs/GNANI_CONFIGURATION.md` and `docs/MULTILINGUAL_WORKFLOW.md`. Those hosted stages require validated internet and are optional; local SOS and BLE messaging do not depend on them. Review translations and AI suggestions before using them in an emergency. In India, call 112 for urgent emergency assistance when possible.

## SahayakNet disaster-response integration

The primary Assistant destination is now the **Response** workspace. It collects structured incident, people-impact, resource-demand, volunteer-availability and safe-zone reports. Reports are broadcast through the existing MeshLink group text transport; delivery remains best-effort and depends on authenticated BLE peers and acknowledgements. Optional cloud sharing requires a separate explicit consent and is staged in the durable outbox.

The previous Gnani processing workflow remains accessible from **More → Gnani voice processing**. It is a processing tool; the Response workspace is the main citizen/field workflow.

The backend now stores `COORDINATION_UPDATE` events as account-owned `CoordinationRecord` JSONB records and exposes responder/admin-only `/api/coordination/summary` plus role-filtered `/api/coordination/records`. Apply `backend/prisma/migrations/202611090002_coordination_records` and regenerate Prisma Client before deploying. See `docs/SAHAYAKNET_INTEGRATION.md` for scope and limitations.


## SahayakNet backend setup note (Windows)

Run backend commands from the `backend` directory. This archive does not include a Node lockfile, so use `npm install` (not `npm ci`) to resolve the dependencies declared in `backend/package.json`. Then run `npm run db:generate` and `npm run db:migrate`. The Android Gradle wrapper is at the project root, not inside `backend`; return to the root directory before running `\.\gradlew.bat assembleDebug`. See `backend/README.md` for environment and PostgreSQL setup.
