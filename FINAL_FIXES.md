# MeshLink v3.0 — final source fixes in this candidate

## Transport / routing
- Broadcast packets (destination = null) are now delivered locally AND forwarded with TTL/hop increment.
- Applies to SOS, ALERT, LOCATION and VOICE_CHUNK broadcast traffic.
- GATT send now reports success only when the write/notification channel is actually ready.
- Optional BLE MTU 247 negotiation added with automatic fallback to the known 20-byte ATT path.
- Larger negotiated frames reduce voice-transfer overhead substantially; legacy devices retain the 20-byte framing path.

## Identity / names
- Scanner's temporary `Authenticating…` name can no longer overwrite a previously authenticated display name.
- HELLO names are cached and update existing peer/location entries.
- Renaming no longer tears down the active BLE scanner/advertiser or GATT sessions; it broadcasts updated HELLO packets instead.
- Map/location labels are refreshed when a peer HELLO provides the real display name.

## Voice
- One logical chat row is created per voice recording using the recording's shared voice message ID, instead of exposing transport chunks as separate UI messages.
- Each completed recording gets its own local file path and Play/Stop control.
- Multiple voice recordings can each be played independently.
- Removed the misleading global `Voice message ready` card from Home.
- Voice recorder is capped at 10 seconds and 8 kbps AAC to reduce offline BLE transfer time.
- Voice player now exposes playback state and a real Stop button; no looping is enabled.

## UI
- Light white/green/teal palette retained and refined.
- Alert / Location / Voice controls are single-line and compact.
- SOS voice attachment shows its own Play/Stop control inside the SOS card.
- Location typography is larger and more readable.
- No fake nodes, distances or locations were added.

## Time display
- Incoming SOS and ALERT cards use the packet's original creation timestamp, so the displayed time represents when the sender created the packet rather than when the receiver happened to process it.

## Build verification note
- Source was inspected after modification.
- Android Gradle build could not be executed in this environment because Gradle 9.6.0 is not cached and external network access is unavailable. Do not treat this ZIP as a prebuilt/verified APK until Android Studio builds it locally.
