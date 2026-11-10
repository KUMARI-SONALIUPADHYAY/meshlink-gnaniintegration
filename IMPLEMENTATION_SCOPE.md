# MeshLink 2.2 — Mentor Feedback Implementation

This build keeps the working BLE/GATT transport and adds only real-data features.

## Added
- Personal chat and Mesh Group chat.
- Real voice-message bubbles with local playback after a complete transfer.
- SOS message text + optional real microphone voice attachment.
- SOS Review: Pass / Reject response packets routed back to the SOS origin.
- Real message timestamps.
- Existing topology retained plus a static offline geographic plotting view using only real GPS fixes.
- Real Location Radar + static offline geographic plot: only GPS coordinates explicitly shared by authenticated peers are plotted.
- Location markers are not generated when a peer has no real GPS fix.
- Alert status is truthful: if no authenticated peer is available it is shown/logged as queued rather than falsely claiming delivery.

## Explicitly not added
- No fake node counts or placeholder notification counts.
- No estimated/fake distances.
- No cloud dependency for the mesh path.
- No random demo coordinates.
- No resource-sharing feature.
- No replacement of the working BLE/GATT layer.
- SOS review states: Review → Pass/Reject.

## Testing expectation
Build this project in Android Studio on the same environment where the known-working baseline was already built successfully. Then physically test at least two phones for text, voice, SOS Pass/Reject, group broadcast, and GPS sharing before treating the feature set as validated.

## Safety / Vulnerability update
- Added a deterministic offline Local Safety & Vulnerability Engine for suspicious financial/credential requests, links, urgency/forward-chain language, excessive punctuation, repetition and message flooding.
- High-risk non-emergency traffic is BLOCKED locally before delivery/forwarding; medium-risk traffic is HELD for local review.
- SOS content is not classified as false by the rule engine; SOS receives priority and only a dedicated SOS rate limit can place it in HOLD.
- Held/blocked packets are persisted in Room so the Safety Review screen can show the actual message and rule reason.
- Manual PASS releases a reviewed packet to local delivery and/or mesh forwarding.
- No cloud AI/API and no claim that the engine can determine real-world truth.
