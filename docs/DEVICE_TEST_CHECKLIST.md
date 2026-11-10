# MeshLink 2.0 real-device validation checklist

Record device model, Android version, app build hash, date, test operator, outcome and evidence for every run. Do not mark a test passed unless it was executed on physical phones.

## Before testing

- [ ] Build and install the same APK on all test phones.
- [ ] Turn ON the Android Bluetooth radio in system settings on every phone. The in-app `Mesh` switch only starts/stops MeshLink; it does not enable Android Bluetooth.
- [ ] Grant Nearby Devices / Bluetooth scan, connect and advertise permissions as prompted. If Android asks whether MeshLink may find/connect to nearby devices, allow it.
- [ ] Grant location only for tests that explicitly share GNSS.
- [ ] Grant microphone only for voice recording.
- [ ] Grant notification permission where requested.
- [ ] Confirm each phone has a unique MeshLink node identity.
- [ ] Record the Android version and battery-optimization state.

## Offline test matrix

First confirm BLE discovery and connection with Bluetooth ON on both phones. Then turn Wi-Fi and mobile data OFF on every phone. Do not use an Internet relay or hotspot. Internet availability does not affect BLE discovery, but Android Bluetooth must be switched on separately.

| Test | Setup / action | Pass condition | Result |
|---|---|---|---|
| 2-phone discovery | Bluetooth ON on A and B; enable Mesh on both; keep screens awake and phones within 1–2 m initially | Both devices first appear as Discovered, then authenticate and show each other as Connected | ☐ |
| Personal chat | A sends a direct message to B | B receives one message; A displays Delivered only after ACK | ☐ |
| Group/broadcast chat | A broadcasts to B and C | Each reachable peer receives one copy | ☐ |
| 3-phone relay | A reaches C only through B | C receives A's message; relay hop/TTL changes are visible in event history | ☐ |
| Duplicate suppression | Deliver same packet via alternate paths/retries | One application-level message is stored | ☐ |
| Store-and-forward | Break downstream link, queue a message, restore link | Queue persists and delivery resumes once link returns | ☐ |
| Voice note | Record short voice note on A and send to B | B reassembles the matching asset and plays audio | ☐ |
| SOS local alarm | Trigger SOS on A | Local siren/vibration starts; location remains absent unless opted in | ☐ |
| SOS delivery | A→B or A→B→C SOS | Receiver shows SOS; sender changes to Delivered only after matching ACK | ☐ |
| Offline persistence | Force-stop/reopen app after queued message | Room data survives process restart; expired items are handled | ☐ |
| Background | Screen off / app backgrounded | Service behavior is recorded; OS/OEM restrictions are noted | ☐ |

## Online test matrix

Restore connectivity. Use only test accounts, non-sensitive sample audio and non-real emergency reports.

| Test | Required setup | Pass condition | Result |
|---|---|---|---|
| Prisma STT | Valid local Gnani key; supported language; Internet | Real provider transcript returned and stored separately from original audio | ☐ |
| Evon analysis | HTTPS OpenAI-compatible inference endpoint and model alias | Validated analysis returned; visibly advisory and reviewable | ☐ |
| Translation | Google Cloud Translation enabled, billing and restricted API key | Real translated text returned and source text retained | ☐ |
| Timbre TTS | Gnani key; supported language/voice pair | Real WAV response validates and plays | ☐ |
| Full voice workflow | Prisma + Evon + Translation + Timbre configured | Transcript → advisory extraction → explicit translation → synthesized WAV | ☐ |
| Cloud sign-in | Deployed HTTPS backend and test account | Authentication succeeds; token persists encrypted across app restart | ☐ |
| Incident create | Test report, explicit location/media consent as appropriate | Server accepts report; no unconsented location/media is uploaded | ☐ |
| Sync retry | Temporarily interrupt network during outbox sync | Unacknowledged event remains pending and retries safely | ☐ |
| Dashboard | Authenticated account; backend data available | Dashboard shows server-backed incident status and cached/offline labels accurately | ☐ |
| Authorization | Community account attempts responder-only change | Server rejects the operation | ☐ |

## Evidence and sign-off

- [ ] Capture short screen recording and device/event logs without credentials or personal data.
- [ ] Record failed tests and exact reproduction steps; do not hide them from judges.
- [ ] Verify no key/token appears in logcat, screenshots or exported demo files.
- [ ] Verify emergency claims are clearly labelled as community communication, not official dispatch.
- [ ] Sign off each phone/service test separately; source presence alone is not a pass.
