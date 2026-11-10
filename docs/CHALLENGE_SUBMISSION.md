# MeshLink 2.0 — Challenge submission brief

## Project description

MeshLink 2.0 is an offline-first emergency communication Android prototype designed for situations where mobile data or Internet infrastructure is unavailable, congested or unreliable. It combines local Bluetooth Low Energy (BLE) peer communication with an optional online multilingual voice-assistance workflow and a separate community incident-coordination backend.

## The problem

During local emergencies, people may be unable to reach one another because cellular coverage, mobile data or Internet services are degraded. People also need a way to share concise emergency information across language barriers. A normal cloud-only messaging design fails when its network dependency is unavailable.

## Why offline BLE

BLE allows nearby Android devices to discover one another and exchange small packets without Wi-Fi or mobile data. MeshLink authenticates peer identities, sends encrypted application packets, and relays eligible traffic through other participating devices with TTL/hop limits and duplicate protection. Messages and queued work are persisted locally so a temporary link loss does not immediately discard the item. Actual range and multi-hop reliability depend on device hardware, radio conditions, Android background restrictions and must be demonstrated on real phones.

## How the two modes work

- **Offline Mode:** local Room storage, BLE discovery/GATT, text/voice transfer, relay, acknowledgements, SOS alarm and optional local GNSS. No AI provider or cloud request is required for these local paths.
- **Online Mode:** retains the local mesh while enabling configured Internet-dependent AI and cloud actions when the connection is validated. Cloud incident summaries are explicitly consent-gated and synchronized through a durable outbox to the project backend.

## Multilingual voice workflow

1. **Prisma v2.5** converts a selected voice recording into a transcript through the Gnani speech endpoint.
2. **Evon v3.3** can extract a structured emergency summary (type, severity, mentioned location, affected count, immediate assistance, warnings and suggested response) through a separately hosted OpenAI-compatible HTTPS inference endpoint.
3. **Google Cloud Translation Basic v2** provides explicit translation when configured; it is separate from Evon because the Evon model card does not establish dedicated translation reliability.
4. **Timbre v2.5** converts selected text into speech using a supported language/voice pair and returns WAV audio.

Original recordings and transcripts are retained separately from analysis, translation and generated speech. AI output is advisory and must be reviewed by a person. The app does not infer missing facts or claim responders have been contacted.

## Architecture and security

The Android client uses Kotlin, Jetpack Compose and Material 3. `MeshEngine` owns BLE/GATT, authenticated peer sessions, routing and message processing. Room is the local persistence layer. Device keys are held in Android Keystore; HELLO uses ECDSA identity authentication, peer sessions use ECDH-derived keys, and application payloads use AES-GCM. Replay/deduplication, TTL, bounded frame/voice reassembly and local rate/safety rules constrain malformed or abusive traffic. The optional cloud service uses Node.js/Express, PostgreSQL, input validation, password hashing, short-lived JWTs, role checks, private media and idempotent sync. Android cloud tokens are encrypted with an Android Keystore-backed key.

## What is implemented vs. verified

The integrated source includes seven main sections (Home, Chats, Nearby, SOS, AI Voice, Reports and Profile), BLE and messaging modules, local SOS/voice paths, separate AI provider clients, Room-backed AI/outbox data and a backend source project. Static checks and targeted JVM protocol/codec smoke tests passed in the development workspace.

The final Android Gradle build and APK were **not verified in the development workspace** because Gradle 9.6.0 could not be downloaded and no Android SDK was installed there. Physical BLE multi-hop tests, live provider calls and deployed cloud tests have not been run. Challenge materials and demo narration must preserve this distinction. Use the device checklist to establish actual end-to-end results before claiming them.

## Safe claims and limitations

MeshLink is a prototype for community communication and information organization. It does not replace official emergency numbers or dispatch systems, does not guarantee delivery or rescue, and does not verify that a crowd-sourced incident is true. A working BLE demo, when physically validated, demonstrates communication between participating devices only. AI-generated summaries, translations and speech may be incorrect and require human review. The cloud backend requires independent deployment and operational security work before public use.
