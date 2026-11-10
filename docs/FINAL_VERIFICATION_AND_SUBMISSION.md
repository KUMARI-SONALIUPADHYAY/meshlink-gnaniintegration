# MeshLink 2.0 — Final verification and submission status

**Audit date:** 9 November 2026  
**Source baseline:** Stage 10 integrated application, plus the final reliability/status corrections in this delivery.

## Executive status

The source tree and submission documentation are prepared. The project has not been compiled into an Android APK in this environment: the Gradle wrapper could not download Gradle 9.6.0 because DNS/network access to `services.gradle.org` failed (`UnknownHostException`), and no Android SDK installation is available in the workspace. Do not describe this delivery as a successful Android build or a device-tested release.

## Verification matrix

| Area | Implemented in source | Build verified here | Automated checks run here | Real-device / external verification |
|---|---|---|---|---|
| Seven-section Compose navigation | Yes | No | Kotlin parser and source invariants only | Not run |
| Online / Offline mode gating | Yes | No | Source inspection | Not run |
| BLE discovery, GATT, authenticated peer sessions | Yes | No | BLE frame codec JVM smoke test; source inspection | Not run |
| Text, personal/group chat, relay, ACK, store-and-forward | Yes | No | Packet codec smoke test; source inspection | Not run |
| Local SOS, category/severity, optional GPS, alarm | Yes | No | SOS source invariants and safety smoke test | Not run |
| Voice record, BLE chunking/reassembly and playback | Yes | No | Voice chunk codec smoke test | Not run |
| Prisma speech-to-text client | Yes | No | Static source/config review | No live API call |
| Evon OpenAI-compatible inference adapter | Yes; requires a separately provisioned HTTPS inference server | No | Static source/config review | No live inference |
| Google Cloud Translation Basic v2 client | Yes; requires a configured Google Cloud key/API | No | Static source/config review | No live API call |
| Timbre text-to-speech client | Yes | No | Voice catalog JVM smoke test; source review | No live API call |
| Cloud incident API and sync outbox | Yes; backend source included, deployment is external | No | Node syntax checks; source review | Backend not deployed or contacted |
| Room v2→v6 migrations | SQL migration source included | No | Migration/source inspection only | No emulator/database migration run |
| Security/privacy controls | Yes, as described below | No | Secret/config scans and source inspection | No penetration test or device validation |

### Checks that passed in this workspace

- Kotlin parser pass across the Android main source set: no parser-level `expecting`, `unexpected tokens`, redeclaration, or conflicting-overload diagnostics. A standalone `kotlinc` invocation cannot type-check Android/Compose/Hilt/Room code without the Android and dependency classpaths; its unresolved Android references are not a successful compile.
- JVM smoke tests passed for BLE frame reassembly, voice chunk encode/decode, packet codec round-trip, invalid-TTL rejection, SOS safety-policy priority, and Timbre language/voice catalog consistency.
- Android manifest and resource XML are well-formed.
- Node.js syntax checks passed for backend JavaScript files.
- Static integration checks passed for the seven navigation sections, saveable navigation state, data-derived dashboard, cloud sign-in guard, SOS GPS opt-in default, immediate local alarm ordering, and bounded GPS lookup.
- SQLite smoke execution of the v5→v6 cloud migration statements passed, including new columns, index creation and `updatedAt` backfill. This does not replace an on-device Room migration test.
- ZIP archive integrity passed for the delivered project archive.

### Checks not run / not passed

- `assembleDebug`, `testDebugUnitTest`, instrumentation tests, lint, and APK generation: blocked before Gradle configuration because Gradle 9.6.0 could not be downloaded.
- Room migration execution: requires Android/Room runtime or a device/emulator test environment.
- Two-, three-, or four-phone BLE testing: not run.
- Prisma, Evon, Translation, Timbre, and cloud backend requests: not run; no live credentials/endpoint were supplied or used.
- Backend integration, production TLS, database backup/restore, and deployment health checks: not run.

## Build environment and Windows instructions

Project configuration:

- Gradle wrapper: **9.6.0** (`gradle/wrapper/gradle-wrapper.properties`)
- Android Gradle Plugin: **9.4.1**
- Kotlin / Compose compiler plugin: **2.4.20**
- Compile SDK: **37**
- Target SDK: **37**
- Minimum SDK: **24**
- Java source/target compatibility: **17**
- Recommended Android Studio Gradle JDK: **JDK 21** (use the Android Studio bundled JDK if it is 21)

1. Install/update Android Studio to a version that supports AGP 9.4.1.
2. In **Tools → SDK Manager → SDK Platforms**, install Android SDK Platform 37. Install the compatible Android SDK Build-Tools and Platform-Tools offered by that SDK Manager.
3. In **File → Settings → Build, Execution, Deployment → Build Tools → Gradle**, select the bundled JDK 21 (or another Gradle-9.6-compatible JDK 17+).
4. Open the extracted `MeshLink_2.0` project folder, not the ZIP itself. Allow Gradle sync to finish.
5. If Gradle distribution download fails, configure the corporate/university proxy under Android Studio HTTP Proxy or allow `services.gradle.org`, `downloads.gradle.org`, Google Maven and Maven Central through the network. Do not change the wrapper version merely to bypass the network restriction. The wrapper distribution checksum is pinned in the project.
6. From PowerShell in the project root, run:

   ```powershell
   .\gradlew.bat --version
   .\gradlew.bat clean testDebugUnitTest
   .\gradlew.bat assembleDebug
   ```

7. If the unit tests pass, install the debug APK on a test phone:

   ```powershell
   .\gradlew.bat installDebug
   ```

8. APK output, if the build succeeds, is:

   `app\build\outputs\apk\debug\app-debug.apk`

If a dependency download fails after Gradle itself is installed, check the proxy/firewall and retry `.\gradlew.bat --refresh-dependencies testDebugUnitTest assembleDebug`. Do not commit Gradle caches or SDK files into the project ZIP.

## Local configuration

The archive intentionally excludes root `local.properties` and `backend/.env`. Preserve the Android SDK line generated by Android Studio and add only the services you actually provision:

```properties
# Keep the sdk.dir line Android Studio created.
gnani.api.key=YOUR_GNANI_API_KEY_ID
evon.base.url=https://YOUR_TLS_INFERENCE_HOST/v1
evon.model=gnani/gnani-evon-v3.3-30B-A3B
evon.api.key=YOUR_OPTIONAL_INFERENCE_BEARER_TOKEN
translation.api.key=YOUR_GOOGLE_CLOUD_TRANSLATION_API_KEY
cloud.api.base.url=https://YOUR_PUBLIC_MESHLINK_API_HOST
```

All properties are optional at build time. An absent provider configuration must remain visibly unavailable rather than being replaced by simulated output. Gnani credentials are used by Prisma/Timbre; Evon is a separate, self-hosted OpenAI-compatible inference service; Translation is a separate Google Cloud Translation API; the cloud URL points to the included Node/PostgreSQL backend.

**Credential warning:** local debug BuildConfig fields are extractable from a debug APK. Never distribute a debug APK containing real provider keys. The release variant deliberately sets provider credentials to empty; use a secured backend/proxy for production secrets. Do not paste secrets into chat, source control, issue trackers, screenshots, or demo recordings.

Backend setup and server-side secrets are documented in [`../backend/README.md`](../backend/README.md). Use a private `.env`, PostgreSQL, TLS reverse proxy, backups and monitoring. The included backend is source code only; it is not deployed by this project ZIP.

## Source audit notes

- BLE transport, packet encoding, cryptography, Room persistence, and provider clients were retained; the final corrections do not replace the transport protocol or database schema.
- The foreground service now retains one `MeshEngine.state` collector across sticky start redelivery and cancels it on stop/destruction.
- SOS delivery state distinguishes `QUEUED`, `FORWARDED` (a local GATT write accepted by a next hop), and `DELIVERED` (matching end-to-end ACK received). A successful GATT write is not described as proof that the destination received the SOS.
- Local SOS alarm is initiated before the optional, bounded GPS lookup. Location sharing remains opt-in.
- Provider clients are separate: Prisma, Evon, Translation, and Timbre. Evon is not assumed to be hosted by the Gnani speech API key.
- Cloud outbox synchronization is separate from BLE and local SOS. It requires Online Mode, a validated network, a configured HTTPS backend and a signed-in account.
- No sample peer counts, synthetic coordinates, mock API responses, or simulated BLE connections were added.

## Security and privacy review

- The source scan found no obvious literal API-key, JWT-secret or password assignments in the Android/backend source files inspected. Root `local.properties` and `backend/.env` are absent from the working tree and excluded from the archive. The supplied project tree has no `.git` metadata, so historical Git commits could not be audited.
- No provider API-key or bearer-token logging was found in the inspected Android call paths. Gnani/Evon error handling redacts known credential strings and common authorization/key fields before surfacing provider error details.
- Debug credentials are embedded in debug BuildConfig when locally configured and are extractable from a debug APK. Never distribute such a debug APK. Release BuildConfig provider credentials are empty; production should use a secured proxy/backend.
- Cloud bearer tokens are encrypted using AES-GCM with a non-exportable Android Keystore key. The cloud client rejects non-HTTPS base URLs. The backend hashes passwords, issues short-lived access tokens, reloads roles server-side, and restricts responder/admin actions.
- BLE identity/session code uses Android Keystore EC key material, ECDSA HELLO authentication, ECDH-derived peer keys and AES-GCM payload protection. These mechanisms were source-reviewed but not penetration-tested or verified across device/OEM implementations.
- Location sharing is opt-in for SOS and community reports. Media is selected by the user and size/MIME checked before upload. Local voice/audio is not automatically uploaded by the offline path.
- Packet processing applies protocol-version, TTL, timestamp and payload bounds, replay/deduplication, bounded frame/voice assembly, message expiry and local rate/safety checks.
- Evon output is advisory, requires human review and is not used to mark a community incident verified or to claim that emergency responders have been contacted.

## Known limitations before public release

- Android build and Android test suites must be completed on a machine with the pinned Gradle distribution, SDK 37 and dependency repository access.
- BLE interoperability and multi-hop behavior require physical-device validation across at least three Android phones and varied OEM/Android versions.
- Background execution, Bluetooth permission behavior, notification permission, battery restrictions and foreground-service behavior vary by Android version and manufacturer.
- Cloud backend requires deployment and operational controls. Email verification, MFA, password recovery, refresh tokens, production monitoring and backup/restore exercises are not included.
- Evon needs a separately hosted compatible inference endpoint and sufficient accelerator capacity. No Evon weights or inference server are included in the Android package.
- AI-generated analysis and translation can be wrong or incomplete. Users must review outputs; they are not dispatch instructions or verified facts.
- The app does not connect to police, ambulance, fire services or an official emergency dispatch network and cannot guarantee rescue or message delivery.

## Challenge submission boundary

Describe MeshLink as an offline-first emergency communication prototype with an implemented BLE mesh and optional online AI/cloud components. Demonstrate only workflows that pass the physical test checklist. For untested features, use the words **implemented in source, pending device/service validation**.
