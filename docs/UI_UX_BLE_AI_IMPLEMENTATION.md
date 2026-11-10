# MeshLink 2.0 — UI, BLE and AI integration notes

## Current source baseline

This work starts from the user-supplied `MeshLink_2.0_Gradle_Fix_TrimEnd(1).zip` project, not from a new project. The user-supplied older `mesh22-57.zip` was inspected as the BLE reference. Its project root is `MeshLink_v4.7_SELF_HEALING_CONNECTION_POLICY`.

## UI/UX implementation

- Primary bottom navigation now has four destinations: Home, Chats, Nearby and Assistant.
- SOS is available from a persistent floating action button and the overflow menu.
- Incident Reports and Profile/Settings remain accessible from the overflow menu; the cloud incident/report screen was not removed.
- The light Material 3 palette now uses navy, blue and teal accents with rounded surfaces.
- Home uses separate online/offline gradient identity and keeps internet status distinct from BLE transport state.
- The Home dashboard reports actual discovered BLE beacons separately from authenticated connected peers.
- A received SOS alert card shows sender, timestamp, message/category text, location accuracy when supplied, receive/review state, and Review/Acknowledge/Reject actions. A map action is shown only when real coordinates were included.
- The dashboard explicitly states that online-user discovery is not supplied by the configured backend, rather than showing a made-up online count.
- Nearby shows authenticated peers separately from recently discovered devices still awaiting the cryptographic handshake.

## BLE comparison and changes

The older project and current candidate had byte-identical source for these transport classes at the time of comparison:
- `BleAdvertiser.kt`
- `BleScanner.kt`
- `BleManager.kt` (before lifecycle repair)
- `GattClient.kt`
- `GattServer.kt` (before lifecycle repair)
- `BlePermissionManager.kt`
- `MeshBleConstants.kt`

The older and current `MeshEngine.kt` differ because the current project contains the newer encrypted identity handshake, relay, message acknowledgements, voice and cloud queue features. No older transport code was blindly copied over those additions.

Changes in this integration:
- `BleManager.start()` is now idempotent. It stops scan/advertise when Bluetooth is off or permissions are missing, and safely reuses its advertiser on subsequent starts.
- `GattServer.start()` checks permission and Bluetooth state before reusing an existing server. When Bluetooth is turned off, it closes the stale server so it can be recreated after Bluetooth is enabled again.
- `MeshEngine.stop()` now nulls the stopped BLE manager and GATT server references and clears transport peer/session maps. This fixes a restart path where `startBleTransport()` could see a non-null stopped manager and decline to start a new one.
- The maintenance loop rechecks the local GATT server and BLE manager, allowing recovery after Bluetooth is toggled back on without requiring the whole app process to restart.
- `MeshUiState` now carries `discoveredPeers` independently from authenticated connected `peers`. Discovered counts are time-bounded (20 seconds) and deduplicated by node identity.
- A distinct `bleStatus` is maintained so the UI does not equate the logical mesh service switch with a functioning radio/transport.
- The Home screen now detects the explicit Bluetooth-off state and offers Android's system Bluetooth-enable prompt. The app switch is labelled `Mesh` to make clear that it starts/stops MeshLink's foreground service; it does not switch on the phone's Bluetooth radio.
- Group chat now displays authenticated peer count separately from discovered-but-not-yet-authenticated devices, explains why messages remain queued, and offers a shortcut to Android Bluetooth settings when no authenticated peer exists.
- The top status line is shortened to avoid truncating the useful BLE state on narrow phone screens.

The BLE wire protocol and service/characteristic UUIDs were not changed.

## AI voice assistant experience

- Added an offline emergency quick guide for medical emergencies, road accidents, fire/smoke, unsafe situations, and lost/separated people. It is available without credentials or internet and includes the India emergency number 112. It is general safety guidance, not a substitute for emergency services or professional instructions.
- Added a small fixed English/Hindi emergency phrasebook for urgent communication without a translation API. These are explicitly labelled fixed phrases, not AI or live translation.
- Added a manual emergency-message input to the translation stage, so users can translate a typed message without first recording/transcribing audio. The stage explains the intended flow: enter/reuse text → select target language → translate → review → copy/share or use for speech.
- Added clearer instructions to the Timbre stage explaining when to use text-to-speech and why credentials/internet are needed.

The existing real Prisma → Evon → Google Cloud Translation → Timbre clients and workflow were preserved. UI additions:
- A workflow status panel is visible at the top of the Assistant screen, including loading state and offline/configuration messages.
- Selected audio shows file size, media duration when the media container exposes it, and a local preview playback action.
- Transcription can be retried after a previous result; the UI no longer disables the transcribe button just because a transcript exists.
- The transcript is editable as a review draft before Evon analysis or translation. The original stored transcription remains unchanged unless the workflow explicitly saves a new result.
- Transcript, AI analysis, translation and response text have copy/share actions.
- Language selectors remain limited to the languages represented by the configured Prisma/Timbre catalogs and the translation workflow. Odia is not enabled because it is not present in the current Prisma/Timbre source catalogs.
- Generated Timbre playback controls and existing queued AI retry handling remain in place.

Provider configuration is still required. UI labels indicate configuration, not live service health. No AI output is fabricated.

## Existing functions retained

- Existing chat screen, group broadcast, voice note send/receive and voice playback.
- SOS category/severity/review and optional GPS consent; local SOS remains independent of AI and cloud.
- Existing cloud incident report/dashboard, sync queue, authentication and media handling.
- Existing Profile, Activity and Safety screens.
- Existing Room database schema and migrations; no migration was added by these changes.
- Existing BLE service and packet format.

## Limitations and verification

- The older BLE transport classes were source-compared, not validated on physical devices in this run.
- Bluetooth off/on recovery and the new Android Bluetooth-enable prompt are implemented in source but still require real-device validation. The screenshot supplied for this iteration explicitly showed Android Bluetooth switched off, despite internet being available. The MeshLink service switch is not the Android Bluetooth radio switch.
- BLE scanning and advertising may vary by OEM, Android version, permission state and radio coexistence.
- A discovery beacon is not an authenticated peer. Nearby lists distinguish those states.
- Manual retry is now exposed for queued/forwarded TEXT, SOS, ALERT, LOCATION and SOS_REVIEW packets. It reuses the original message ID and packet payload; delivery is still confirmed only by a matching ACK. Voice chunk retries remain on the existing durable queue path.
- The configured backend does not expose online-user discovery, so the UI does not show an online-user count.
- The existing Profile hub retains profile, mode, cloud account, activity and safety review. Emergency-contact management and granular notification preferences are not implemented in this source baseline; they are not represented as working settings.
- Manual retry is supported for persisted TEXT, SOS, ALERT, LOCATION and SOS_REVIEW packets. It does not add retry controls for individual voice chunks or arbitrary attachments.
- Prisma, Evon, Translation and Timbre requests were not live-tested in this environment.
- No complete Gradle build or APK is claimed unless verified separately on a configured Android Studio environment.

## Screenshot-driven follow-up

- The latest supplied Home screenshot showed `Internet: Connected` and `BLE transport: Bluetooth off`. This is an Android Bluetooth-radio state, not an internet failure. The Mesh switch controls the app's foreground service; Android Bluetooth must be enabled separately on each phone. Home now provides the Android enable prompt and Group Chat provides a Bluetooth settings shortcut.
- The group chat now explains that a queued message is stored locally and is not delivered until an authenticated mesh peer and acknowledgement are available. It distinguishes discovered beacons from authenticated peers.
- The dashboard secondary metrics are more compact; AI setup is displayed alongside queue/cloud metrics instead of as an oversized full-width tile.
- The Assistant now has local emergency instructions and an English/Hindi phrasebook that work without API keys. Phone-native TextToSpeech can read selected phrases or reviewed response text using installed device voices. Offline speech depends on local language voice data being installed.
- Manual emergency text can now be entered directly for translation, without first recording audio. The UI explains the translation/review/speech workflow. Timbre remains an optional cloud stage and still requires credentials and internet.
