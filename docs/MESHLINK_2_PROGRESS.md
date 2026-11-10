# MeshLink 2.0 — Implementation progress

## Implemented

- Added a first-run Online Mode / Offline Mode selection screen.
- Persisted the selected mode in the activity's private preferences.
- Added a Mode action in the main app bar so the user can change the selection later.
- Added an internet connectivity indicator using Android `ConnectivityManager` and `NET_CAPABILITY_VALIDATED`.
- Added a separate BLE status indicator based on the existing `MeshEngine` state.
- Added `ACCESS_NETWORK_STATE` permission for connectivity observation.
- Kept BLE service startup and shutdown under the existing Mesh switch. The internet callback does not stop, restart, or alter the BLE service.
- Updated app version metadata to version name `2.0`, version code `9`.

## Connectivity behavior

Online and Offline are user-selected application modes. The mode selector does not yet invoke a cloud backend. Both modes retain the existing local BLE mesh controls. If internet connectivity is lost while Online Mode is selected, the internet indicator changes to unavailable; no BLE stop action is triggered.

## Not included in this stage

- Gnani Prisma, Evon 30B, or Timbre API integration.
- Translation service integration.
- Cloud authentication, incident reporting, synchronization, or dashboard.
- Automatic BLE permission prompting at mode selection. The existing Mesh switch continues to control BLE startup and permissions.

## Manual verification

1. Install the debug build and launch MeshLink. Confirm the mode selection screen appears on first launch.
2. Select Offline Mode. Confirm the main application opens and the app bar shows `Offline mode` plus separate Internet and BLE statuses.
3. Turn the existing Mesh switch on and grant the requested Bluetooth permissions. Confirm the BLE status changes to active.
4. Turn Wi-Fi and mobile data off. Confirm Internet changes to unavailable while BLE remains active.
5. Turn connectivity back on. Confirm Internet changes to available without the app restarting the BLE mesh.
6. Tap `Mode`, select Online Mode, and confirm the selection persists after force-closing and relaunching the app.
7. Repeat the internet-loss check in Online Mode. BLE must remain active independently of internet availability.

## Build verification status

Build and unit tests could not be executed in the current Linux workspace: the Gradle 9.6.0 distribution is not cached and the environment cannot resolve `services.gradle.org`. No Android SDK is installed in this workspace. Run `gradlew.bat testDebugUnitTest` and `gradlew.bat assembleDebug` in Android Studio/PowerShell on the configured Windows development machine.

## Stage 2 — BLE/voice reassembly hardening

The BLE frame reassembler now expires incomplete assemblies after 30 seconds, caps active assemblies at 8, caps a packet at 4,096 frames / 512 KiB, validates frame payload bounds, and evicts the oldest incomplete assembly when the active-assembly limit is reached. The voice reassembler now expires incomplete transfers after 2 minutes, caps active transfers at 4, limits a voice to 256 chunks / 32 KiB / 15 seconds, validates chunk sizes, and removes an assembly when metadata is inconsistent.

A JVM unit test was added for rejecting unreasonable BLE frame counts. These are source-level changes; they have not yet been run through Gradle or tested on physical phones because the current workspace cannot download the Gradle distribution or access an Android SDK.

## Stage 3 — Gnani Prisma and Timbre REST preparation

- Added `core/ai/GnaniVoiceApi.kt` using Android `HttpURLConnection` on `Dispatchers.IO`.
- Prisma: multipart `POST https://api.vachana.ai/stt/v3`, `X-API-Key-ID`, `audio_file`, `language_code`, and `format=transcribe`. It parses the actual JSON `transcript` field, reports HTTP/provider errors, and accepts the current app's M4A recording format. The existing recording cap is 10 seconds; the current Prisma docs permit clips up to 30 seconds.
- Timbre: JSON `POST https://api.vachana.ai/api/v1/tts/inference`, `X-API-Key-ID`, `timbre-v2.5`, voice/language, WAV/PCM config. It saves the raw binary WAV response and does not fabricate audio.
- Added a sixth `AI Voice` tab. It can transcribe the latest local voice recording and synthesize typed/transcribed text; playback uses the existing `VoicePlayer`.
- Gnani actions are disabled unless Online Mode is selected, internet is validated, a key is configured, and no request is running. These APIs do not participate in BLE, offline messaging, or SOS.
- Added `INTERNET` permission (kept separate from `ACCESS_NETWORK_STATE`).
- Gradle reads `gnani.api.key` from root `local.properties`, or `GNANI_API_KEY` from an environment/Gradle property. No credential is included in this project archive.
- Added `docs/GNANI_CONFIGURATION.md` with setup instructions and the important warning that a key compiled into an Android APK can be extracted. A backend proxy is required for production-grade secret protection.
- Evon is not wired into Android: the official model card describes an open-weight 30B/approximately 3.5B-active BF16 model and documents Transformers/vLLM/SGLang with Linux + NVIDIA H100 80GB/H200/A100 class hardware. A separately provisioned inference host is required; no hosted Gnani Evon endpoint has been invented.

### Verification in this workspace

- `GnaniVoiceApi.kt` compiled with the local Kotlin compiler against minimal Android/Hilt/JSON stubs and the bundled coroutine runtime. This checks Kotlin syntax/types for the client but is not an Android Gradle build.
- `BleFrameCodec.kt` and `VoiceTransfer.kt` compiled with the local Kotlin compiler.
- Executed a JVM smoke test: 1,500-byte BLE payload round-tripped over 137 legacy-size frames; 997-byte voice payload round-tripped over 9 chunks.
- AndroidManifest.xml parsed successfully.
- MainActivity was parsed by the Kotlin compiler but cannot be fully type-checked without Android/Compose dependencies.
- Full Gradle build and Android/JUnit test suite remain blocked: wrapper download fails with `UnknownHostException: services.gradle.org`; no Android SDK is installed in this workspace.
- No live Prisma/Timbre request was sent because no API key has been configured. No physical-device BLE test was performed.

### Manual verification after local setup

1. Add `gnani.api.key=YOUR_KEY` to the root `local.properties`, preserving the existing `sdk.dir` line; never commit or share that file.
2. Sync Gradle and run `gradlew.bat testDebugUnitTest` then `gradlew.bat assembleDebug` in Android Studio/PowerShell.
3. Install the APK. Select Online Mode, enable BLE from the existing switch, and verify the app bar reports internet and BLE separately.
4. Record a voice note from Mesh or Chats, open AI Voice, select `hi-IN`, and run Prisma transcription. Confirm a real transcript appears.
5. Use the transcript or type a sentence, keep Timbre voice `Nalini`, and generate speech. Confirm a WAV file is returned and plays.
6. Turn off Wi-Fi/mobile data. Gnani buttons should disable; existing BLE messaging and SOS should remain available.
7. Select Offline Mode. Gnani buttons should remain disabled even if internet happens to be available.


## Stage 4 — Local Gnani credential isolation and client separation

- Split the previous combined network implementation into `PrismaApiClient` and `TimbreApiClient`; `GnaniVoiceApi` is now a thin UI compatibility facade only. Each client owns its own endpoint, request encoding, response parsing, and validation.
- Added `GnaniApiCredentials`, which reads the Gradle-provided local key without exposing it through UI state or logging. Both clients set the exact `X-API-Key-ID` header.
- Gradle now defaults the credential to empty for all variants, injects it only into the local **debug** build, and explicitly keeps it empty in **release**. The developer key remains in the ignored root `local.properties`; no secret is stored in Kotlin source or this archive.
- Expanded `.gitignore` for local properties, `.env` variants, `secrets.properties`, and local keystore files.
- Added bounded, sanitized error mapping for missing/invalid keys, permission errors, unsupported media/size, rate limits, provider errors, network failures, and timeouts. The raw API key and request headers are never logged or placed in errors.
- Prisma rejects unknown audio extensions before upload and parses the documented JSON transcript response. Timbre validates the response media type and WAV RIFF/WAVE signature before making the file playable.
- Updated `docs/GNANI_CONFIGURATION.md` with local setup, test steps, API contract references, release/debug credential behavior, and the backend-proxy security boundary.

### Verification

- All five Gnani client/configuration Kotlin files compile with local `kotlinc` against lightweight Android/Hilt/JSON/coroutine stubs. This is a source syntax/type check only, not a full Android build.
- Confirmed the build configuration reads the root `local.properties`, uses `X-API-Key-ID` in both clients, and explicitly clears the key in release.
- No live provider request was sent because the key is configured only on the user's machine. No key was requested or copied into this workspace.
- Full Android Gradle build remains blocked in this workspace by the unavailable Gradle distribution/Android SDK. Run the Windows commands in the configuration guide to verify the actual app.

### Additional emergency-path hardening in Stage 4

- Hardened the local SOS attention signal so audio and vibration are attempted independently; failure of one does not suppress the other.
- Added the legacy vibration fallback for Android API 24–25 (the app's minimum SDK), while using `VibrationEffect` on API 26+.
- A local SOS siren is now triggered only after the incoming SOS payload passes structural parsing. Malformed SOS payloads are logged as rejected and cannot cause a false local siren.
- This is source-level hardening only; actual speaker/vibrator behavior still requires device testing.

## Stage 5 — Prisma v2.5 emergency voice transcription

- Verified the official REST contract: `POST https://api.vachana.ai/stt/v3`, `X-API-Key-ID`, multipart `audio_file` and `language_code`; `format=transcribe` is supported, and omitting `model` selects Prisma v2.5 by default.
- Added `X-API-Request-ID` using the stable MeshLink voice-message ID. The API client reports upload and processing phases separately.
- Enforced the provider's 30-second maximum and supported formats (WAV, MP3, OGG, FLAC, AAC, M4A). The app's 25 MB file guard is explicitly a local safety limit, not a Gnani-published byte limit.
- Corrected empty transcript handling: the official API can return an empty string when no speech is detected; MeshLink now reports this as a successful no-speech result.
- Added audio-file selection to the AI Voice screen. Imported files are copied to app-private persistent storage. Recorder output now uses the same persistent directory rather than cache.
- Added a separate Room `voice_transcriptions` table keyed by logical message ID and a version 2→3 migration. Original audio remains untouched.
- Connected the same voice-message ID through normal voice-note sending and SOS voice attachment so the transcription and mesh payload can share a stable ID.
- Added user-visible recording, upload, processing, success and failure statuses. Prisma remains gated by Online Mode, validated internet and local credential presence; BLE/SOS code paths remain local.
- Verification in this environment is static/source-level only. Full Android Gradle build and real-device/API testing are intentionally deferred until the implementation sequence is complete, per the requested workflow.

## Stage 6 — Evon self-hosted Indic assistant adapter

- Added a configurable Android client for Gnani's Evon v3.3 model using the OpenAI-compatible `/v1/chat/completions` interface documented in the official model card.
- The client requires an HTTPS inference base URL, accepts a configurable served-model name, and supports an optional server bearer token. It does not assume that the Prisma/Timbre API key is an Evon credential and does not invent a Gnani-hosted endpoint.
- Added an Evon assistant card to AI Voice. Users can pass the Prisma transcript as context and receive an Indic-language response. Requests are Online Mode + validated internet only; no Evon request is made in Offline Mode.
- Endpoint/token configuration is read from root `local.properties`, which is ignored by Git. BuildConfig receives values only in debug; release fields are blank.
- The app explicitly warns that Evon output is advisory and does not dispatch responders or verify facts. Model execution remains on a separately provisioned Linux/NVIDIA inference server; no model weights are bundled into Android.
- Configuration is optional. Without `evon.base.url`, the Evon action stays disabled; the Prisma and Timbre functions are unaffected.


## Stage 7 — Gnani Timbre v2.5 integration

- Verified the REST contract and model-specific voice catalog against Gnani's official docs: `POST https://api.vachana.ai/api/v1/tts/inference`, `X-API-Key-ID`, JSON request, and raw binary WAV response.
- Added strict language/voice pair selection for the 42 Timbre v2.5 voices across the documented language catalog. Unsupported language/voice combinations are rejected before network use.
- Hardened the Timbre client: local text guardrail, provider error mapping, network/timeouts, content-type validation, 25 MB local response cap, RIFF/WAVE signature validation, atomic save into app-private persistent storage, and no key logging.
- Added `generated_speech` Room entity/DAO and a non-destructive database migration from v3 to v4. Generated WAV files are indexed by the originating emergency message ID and retain model/language/voice metadata.
- Connected the AI Voice UI: Prisma transcript → Evon prompt/answer → Timbre synthesis, with direct text entry for user-supplied translated text. Added language and voice menus restricted to the official v2.5 catalog.
- Extended native voice playback with play, pause, resume and replay controls.
- Kept Timbre gated behind Online Mode, configured credentials and validated internet; BLE messaging and SOS are independent.
- Incremented Android versionCode to 13.
- Static checks performed: official endpoint/header/payload/response contract cross-checked; Room migration and call-site consistency inspected; credential remains sourced only from ignored local configuration. Full Android build and real provider/device testing are deferred per the implementation-first workflow.

## Stage 8 — Multilingual emergency voice workflow and coordination foundation

- Added a dedicated AI Voice recording action in the assistant tab, while retaining audio-file selection and the existing persistent voice-note path. Prisma language is now selected from the supported locale list rather than entered as arbitrary text.
- Prisma transcription remains stored separately from original audio. On successful transcription, the app can automatically request Evon analysis when a real HTTPS inference endpoint is configured.
- Added `EvonEmergencyAnalysis` with strict JSON extraction and local validation for emergency type, severity, mentioned location, affected-person count, immediate assistance, instructions, warnings, and a suggested response. The original transcript is preserved and the output is visibly labeled AI-generated/advisory.
- Verified the Evon v3.3 model card: it supports English plus ten Indic languages for comprehension/chat/reasoning, but the card does not provide a dedicated translation benchmark or translation-quality guarantee. The app therefore does not claim Evon is a reliable emergency translation engine.
- Added a separate Google Cloud Translation Basic v2 client using the official REST endpoint and response shape. The UI can translate the original report and Evon response separately into target languages that have matching Timbre v2.5 voice options. The API key is local debug-only configuration; no credentials are committed.
- Connected translated responses to the existing Timbre client and stable message ID. Translation and generated speech results are saved in Room without replacing the original transcript or audio.
- Added Room v4→v5 migration and entities for structured emergency analysis, translations, durable AI retry work, and a consent-gated cloud sync outbox. Connectivity restoration and app resume retry queued Prisma/Evon/translation/Timbre stages in Online Mode. Offline mode and local SOS/BLE paths do not use these services.
- Added explicit confirmation before sharing a reviewed AI-generated emergency summary over the local mesh. This is a normal mesh broadcast; it does not trigger SOS or contact responders.
- Added a cloud sync foundation only: a consent-gated local outbox is present, but no remote backend is configured and no cloud upload is claimed. The next cloud stage needs a chosen backend, authentication model, authorization, privacy/retention rules, and real transport.
- Updated Android versionCode to 15.

### Static verification and limits

- Kotlin parser check completed with zero syntax (`expecting`) errors. The standalone compiler cannot fully type-check Android/Compose/Room sources because the Android SDK and dependency classpath are not installed in this environment.
- Room v5 migration SQL was checked against the new entity field definitions using a local SQLite smoke check.
- Checked that translation/Gnani/Evon secrets are read only from ignored local configuration, release BuildConfig fields are empty, and no secret values are present in the project archive.
- Full Gradle tests/build remain blocked because Gradle 9.6.0 cannot be downloaded (`UnknownHostException: services.gradle.org`) and no Android SDK is installed here.
- No live Google Translation, Prisma, Evon or Timbre requests were sent. No phone testing was performed, consistent with the implementation-first workflow.

## Stage 9 — secure cloud synchronization and emergency coordination

- Inspected Stage 8: existing Room outbox was only a local staging placeholder; no Firebase project or Firebase SDK was configured. Selected a project-owned Node.js/Express/PostgreSQL backend to avoid duplicate unconfigured infrastructure.
- Added authenticated community registration/login, short-lived JWT sessions, role lookup from database, responder/admin-only incident status and verification, community dispute flow, incident CRUD/read endpoints, idempotent event sync, private media upload/retrieval, rate limiting, Zod validation, bcrypt password hashes and Helmet headers.
- Added Android HTTPS API client, Android Keystore AES-GCM token storage, cloud dashboard navigation, account sign-in, report form, optional consented GPS and image, nearby query, incident details/status/verification/dispute, cached offline incidents and map deep links.
- Upgraded Room to v6 with outbox attempts/error/retry metadata and cloud incident cache; removed destructive fallback migration.
- Cloud outbox uses stable event IDs, serialized uploads, 30-second retry backoff, persisted media IDs and server-side idempotency. Reports are locally queued before cloud upload. Future SOS summary sync is a separate opt-in and is staged only after `MeshEngine.sendSos` returns the actual packet ID. SOS transmission itself is unchanged and precedes cloud operations.
- Added backend Docker deployment, environment template, operator-only responder provisioning, and a retention task.
- Remaining: deploy backend + PostgreSQL behind HTTPS; set `cloud.api.base.url` in local.properties; operational hardening (email verification, MFA, password recovery, refresh tokens, backups, monitoring); in-app map SDK and object storage for multi-replica production.
- Static checks: Node syntax checks pass; full Android Gradle build remains unavailable in this environment due the missing Gradle distribution/Android SDK.

## Stage 10 — application-wide integration and navigation refinement

- Reorganized the main navigation into Home, Chats, Nearby, SOS, AI Voice, Reports and Profile while retaining existing feature screens and service owners.
- Moved the existing Events and Safety review screens into the Profile hub so they remain accessible without consuming dedicated primary navigation slots. Added profile, mode, mesh and cloud-account controls.
- Replaced the earlier mesh-only landing view with a data-backed emergency dashboard: Online/Offline mode, independent internet and BLE status, authenticated peer count, active conversations derived from persisted messages and current peers, pending cloud-sync count, recent high-priority/SOS messages and quick navigation.
- Expanded Nearby with a list of currently authenticated peers and real last-seen/RSSI information. Existing offline map and topology remain in place and do not fabricate locations.
- Added a dedicated SOS form with category, severity, optional location consent and review-before-send. Category/severity are included in the existing SOS text field, preserving the packet wire format.
- Changed the SOS location behavior to be opt-in. `MeshEngine.sendSos` now starts the local alarm immediately and bounds GPS lookup to 1.2 seconds, preventing a stalled location provider from delaying local alarm/mesh transmission. Cloud and AI services remain outside the SOS path.
- Refined message bubbles to visually distinguish SOS, show timestamps and delivery acknowledgements for text and voice messages, and display local delivery state.
- Added saveable navigation state and a Profile hub for identity, communication mode, mesh service, cloud account/sign-out, privacy, activity log and safety review.
- Added `docs/INTEGRATED_APPLICATION.md` documenting screen ownership, service boundaries, privacy and verification limits.
- No BLE packet protocol, existing message IDs, Prisma/Evon/Translation/Timbre API clients, cloud sync backend contract or Room schema was replaced in this stage.

### Stage 10 verification

- Kotlin parser over all main source files: zero parser/syntax diagnostics. Standalone `kotlinc` cannot type-check Android/Compose/Hilt/Room without the Android and dependency classpaths; its unresolved-reference diagnostics are environmental and are not reported as a successful build.
- AndroidManifest XML parses successfully.
- `bash gradlew --offline testDebugUnitTest` remains blocked because the wrapper attempts to fetch Gradle 9.6.0 and this environment cannot resolve `services.gradle.org` (`UnknownHostException`).
- No fresh APK, full Gradle test run, live service verification or physical-device testing is claimed.


## Final verification and submission preparation — 9 Nov 2026

- Audited the final Stage 10 source tree, Gradle configuration, manifest, BLE/mesh entry points, AI clients, cloud API/outbox, Room v6 migration source, service lifecycle and credential configuration.
- Fixed foreground-service sticky-start collector duplication.
- Corrected SOS state semantics: a local GATT write is `FORWARDED`, not end-to-end `SENT`; only a matching ACK sets `DELIVERED`. The BLE packet schema is unchanged.
- Added final build/verification status, API configuration, physical-device checklist, challenge description and timed demo recording script. Updated the local voice-recording cap in the testing guide to the implemented 10 seconds.
- Kotlin parser scan: no parser-level errors detected. Targeted JVM smoke tests passed for BLE frame reassembly, voice chunk codec, packet codec, TTL/SOS safety policy and Timbre catalog. XML parsing, Node syntax checks, static integration checks, SQLite v5→v6 migration SQL smoke test and ZIP integrity also passed.
- Full Android build/test command was attempted (`bash gradlew --no-daemon assembleDebug testDebugUnitTest`) but stopped before project configuration because the Gradle 9.6.0 distribution could not be downloaded (`UnknownHostException: services.gradle.org`). No Android SDK is installed in this workspace. Therefore no APK, full compile, Gradle unit-test result or instrumentation result is claimed.
- No physical BLE tests, live Prisma/Evon/Translation/Timbre calls or deployed backend tests were run. See `FINAL_VERIFICATION_AND_SUBMISSION.md` for the verification matrix and Windows build instructions.

## UI / BLE / AI integration pass (2026-11-09)

- Reworked the primary navigation to Home, Chats, Nearby and Assistant. SOS is exposed as a persistent action; incident reports and profile/settings remain available from the overflow menu.
- Updated Material 3 colors to a navy / blue / teal light theme and added mode-specific dashboard gradients.
- Added separate discovered-beacon and authenticated-connected peer state, time-bounded discovery snapshots, and distinct BLE transport status.
- BLE advertiser/scanner/GATT UUIDs and protocol were compared against the supplied v4.7 reference. The legacy transport source was already identical in the core advertiser/scanner/client/constants/permissions files; the existing newer MeshEngine protocol was preserved.
- Fixed BLE service restart cleanup and added periodic Bluetooth/permission recovery checks. GATT server handles are closed when Bluetooth is disabled or connect permission is revoked.
- Added a Home SOS alert card for received SOS with sender, timestamp, location/accuracy when shared, review/acknowledge/reject actions, and map opening only when actual coordinates are present.
- Added explicit retry for queued/forwarded TEXT, SOS, ALERT, LOCATION and SOS_REVIEW messages. Retry uses the original packet ID and is still subject to normal ACK/deduplication. Voice chunk retries remain on the durable queue path.
- Enhanced the Assistant UI with selected audio preview, file size/duration, transcription retry, editable transcript review, top-level workflow status/loading, and copy/share controls for transcript, AI analysis, translations and response text.
- Profile now shows configured API endpoints/credentials status and app information. Configuration status is not a live provider health check.
- No Room schema migration or BLE wire protocol change was introduced.

### Verification boundary

Kotlin lexical delimiter scan: 73 source files, no delimiter errors. Android XML: 11 files parsed. Version catalog: 26 aliases resolve with no broken version references. Backend JavaScript syntax checks pass for the server and two scripts. Credential-pattern scan found no likely committed API secrets. The Gradle wrapper still cannot download Gradle 9.6.0 in this environment (`UnknownHostException: services.gradle.org`); there is no Android SDK installed here. Therefore no Android compilation, Gradle tests, APK build, live AI provider check, deployed cloud check, or physical BLE test is claimed.

## Stage 11 — Screenshot-driven usability and connection guidance

- Clarified the distinction between the MeshLink foreground-service switch and Android's Bluetooth radio. The top app-bar switch is labelled `Mesh`; Home offers the Android system enable-Bluetooth prompt when the BLE status reports the radio is off.
- Shortened the app-bar connectivity summary for narrow phone screens.
- Reworked the Home network metrics into two compact rows and placed the AI setup status beside the other secondary metrics to avoid the oversized single-card row.
- Group chat now explains the difference between discovery and authenticated connection, shows the number of discovered-but-not-authenticated devices, warns that queued messages are not delivered, and links to Android Bluetooth settings when no authenticated peers are available.
- Added an offline emergency quick guide for medical emergencies, road accidents, fire/smoke, unsafe situations and lost/separated people, plus fixed English/Hindi emergency phrases. These remain available without API credentials or internet.
- Added phone-native Android TextToSpeech as an optional local speech fallback for emergency phrases and reviewed response text. It does not require the Timbre API key; offline playback depends on installed device voice data and language support.
- Added a manual emergency-message input to the translation stage and clarified how the user should enter/reuse, translate, review, share and speak a message.
- Added instructions explaining the intended purpose of the Timbre stage and the difference between cloud-generated saved audio and phone-native speech.

### Screenshot diagnosis and verification boundary

The supplied screenshot explicitly reports `BLE transport: Bluetooth off` while Internet is connected. This means the tested phone's Bluetooth radio was off at the time of capture. Online mode and mobile internet do not establish a BLE link. The app switch controls the MeshLink service, not Android Bluetooth. The UI now guides the user to enable the system radio. The source also retains the earlier BLE self-healing changes, but two-phone discovery, GATT connection, authentication, group broadcast, SOS ACK and three-phone relay still require testing on real devices. No successful physical connection is claimed from screenshots or static checks.

## 2026-11-09 — Chat, SOS recipient and assistant workflow fixes
- Hid the global SOS FAB while Chats is open so it no longer overlaps the message composer send affordance; changed the send control to a filled, empty-state-aware button.
- Added group-vs-personal recipient selection to SOS. The chosen node ID is passed into the existing `sendSos(destination=...)` path; group remains a broadcast.
- Added the Android system Bluetooth-enable prompt when the user enables MeshLink while Bluetooth is off (after runtime permissions are granted).
- Removed automatic clipboard writes from the English/Hindi phrasebook actions.
- Added direct group-send actions for phrasebook text, reviewed transcript, AI analysis, translated text and composed speech response.
- Expanded offline emergency guidance to cover medical, road accident, fire, flood/weather, personal safety, earthquake, lost/separated and other emergencies; clarified India 112 guidance.
- See `docs/CHAT_SOS_ASSISTANT_WORKFLOW_FIXES.md` for scope and verification limits.
