# MeshLink 2.0 — Demo recording script (3–4 minutes)

**Recording rule:** record only after the corresponding test passes on the actual phones. Do not stage fake peers, synthetic coordinates, prewritten AI responses or fabricated cloud incidents. Use test accounts and non-sensitive sample content.

## Setup shot (0:00–0:20)

- Show the MeshLink home dashboard on three Android phones labelled A, B and C for the recording only.
- Show Online/Offline mode and the real BLE mesh status.
- Disable Wi-Fi and mobile data on all three phones. Briefly show the system network toggles.
- Narration: “MeshLink is designed to keep nearby emergency communication available when Internet connectivity is not. The local BLE path does not depend on our cloud services.”

## Offline discovery and relay (0:20–1:15)

- Enable Mesh Mode on all phones and wait for actual authenticated peer discovery.
- Place A and C outside direct BLE range where practical, with B between them.
- Send a unique text such as `MESH-DEMO-1109` from A.
- Show B relaying and C receiving it once; show event/hop details if the build exposes them.
- Show the sender’s delivery state transition only after the matching ACK is received.
- Narration: “Each participating device can forward authenticated packets within the configured TTL. The app persists messages and suppresses duplicate message IDs.”

## SOS workflow (1:15–1:55)

- On A, open SOS, select a test category and severity, leave location off for the first demonstration, review and confirm.
- Show the local alarm/vibration and the SOS appearing on the receiving phone(s).
- If demonstrating GPS, repeat with explicit location consent and show only the consented coordinate.
- Show `QUEUED`, `FORWARDED` or `DELIVERED` accurately; do not call a GATT write an end-to-end receipt.
- Narration: “The local SOS alarm and BLE broadcast are independent of AI and cloud. Location is optional and shared only after consent.”

## Voice note (1:55–2:20)

- Record a short voice note on A and send it to B.
- Show the received asset and play it on B.
- Narration: “Audio is chunked for BLE transfer and reassembled locally under the message ID.”

## Online multilingual workflow (2:20–3:20; only if live services were tested)

- Re-enable Internet and select Online Mode.
- Record or import a non-sensitive test sentence in a supported language.
- Show the real Prisma transcript, then Evon’s clearly labelled advisory extraction if its endpoint is available.
- Explicitly translate using Google Cloud Translation if configured; then synthesize the selected text with a supported Timbre voice.
- Play the real generated WAV.
- Narration: “The provider steps are optional and separate. The original audio and transcript remain intact; analysis and translation are assistive, not verified emergency instructions.”
- If any provider is not configured or has not been tested, omit this segment and say so rather than simulating it.

## Reports and closing (3:20–4:00)

- If the backend is deployed and verified, sign in with a test account and show one consented community report, its sync state and server-backed dashboard entry.
- If not deployed, show the Reports screen as unconfigured/cached and explain that cloud coordination is optional and not part of offline SOS delivery.
- Closing narration: “MeshLink is a community communication prototype. It does not contact official emergency services or guarantee rescue. It aims to keep nearby communication and structured information sharing useful when infrastructure is limited.”
