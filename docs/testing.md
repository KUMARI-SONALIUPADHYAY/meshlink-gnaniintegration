# Physical Validation Matrix

A simulator is not sufficient for BLE mesh validation.

| Test | Setup | Expected |
|---|---|---|
| A | 2 phones A↔B | discovery, authenticated session, text, ACK, reconnect |
| B | 3 phones A-B-C | A→C relay, TTL/hop count, one application delivery |
| C | 4 phones A-B-C-D | direct A-D path unavailable; route through relays |
| D | Wi-Fi OFF + mobile data OFF | text/SOS/location still work |
| E | break C link, send A→D, restore | queued delivery after reconnection |
| F | duplicate same packet repeatedly | one application delivery |
| G | 10–20s voice | metadata, chunk transfer, local reassembly |
| H | repeated/suspicious text | HOLD/BLOCK policy |
| I | SOS | five siren pulses, vibration, sender/location/voice, relay |
| J | screen off/background | foreground service remains active within Android OS limits |

Do not record a requirement as physically complete until it passes on real Android phones.

## Multi-hop / Store-and-Forward / SOS validation

### 3-device relay
1. Put A and B within BLE range; put C within B range but outside A range.
2. Enable Mesh Mode on all three devices.
3. Wait for authenticated peers.
4. From A use **Broadcast to mesh** in Chat and send `MESH-RELAY-TEST`.
5. Verify C receives one application message and Event Log shows relay hops.
6. Verify duplicate delivery does not occur when the same packet reaches a node through another path.

### Store-and-forward
1. Establish A-B-C once so C's identity is known.
2. Break C's BLE link / move C out of range.
3. Send a destination message or queued mesh message from A.
4. Verify the relay node retains the packet with `QUEUED` status.
5. Restore C's BLE link.
6. Verify the maintenance queue retries and C receives the message once.
7. Verify expired packets are removed/marked `EXPIRED`.

### SOS relay
1. Enable Mesh Mode on A, B and C.
2. Tap **SEND SOS** on A and confirm the SOS dialog.
3. Verify A captures GPS when available, enters priority-100 routing, plays five siren pulses and vibrates.
4. Verify B and C surface the sender name and SOS event; coordinates are included only when the sender explicitly opted in and a GPS fix is available.
5. Verify each relay records an Event Log entry and TTL/hop count progresses.

## Final 4-phone validation run

Use four real Android phones and record the test result only after the app build is stable.

1. Phone A, B, C and D: install the same release APK and grant only required runtime permissions.
2. Turn Wi-Fi and mobile data OFF on all four phones.
3. Enable Mesh Mode on all four phones and wait for authenticated peers.
4. Arrange A-B-C-D so A and D are not directly reachable while adjacent devices remain reachable.
5. Send `FOUR-HOP-TEST` from A to D using a destination chat when D is known, or broadcast and verify one application delivery at D.
6. Confirm Event Log shows relay hops and no duplicate application delivery.
7. Move C out of range, send a message toward D, and verify the relay queue retains it.
8. Restore C, wait for reconnection, and verify queued delivery occurs once.
9. Tap **SEND SOS** on A, confirm the SOS dialog, and verify the priority-100 SOS reaches the relay chain with sender name, location representation, five-pulse local siren and vibration.
10. Record the final result for 2-phone, 3-phone and 4-phone scenarios separately.

## Feature completion checklist

- GPS: Android GNSS capture with offline coordinate representation; no cloud map dependency.
- Voice: local AAC/M4A recording capped at 10 seconds, chunking, BLE transfer and local reassembly/playback. Imported audio follows the Prisma client’s separate 30-second / 25 MB guardrails.
- Safety: deterministic local rate limiting/classification plus Pass/Hold/Block review controls.
- Four-phone validation: documented physical test; it cannot be marked complete without real-device execution.
