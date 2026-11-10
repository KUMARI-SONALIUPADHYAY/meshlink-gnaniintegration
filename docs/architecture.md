# MeshLink Architecture

MeshLink is an Android-native offline BLE system. The foreground service owns discovery, GATT server/client links, routing, queueing and emergency events; Compose observes state through `MeshEngine`.

## Transport
- BLE advertiser + filtered scanner using the MeshLink service UUID.
- GATT server RX write / TX notification characteristics.
- MTU request to 247 and application framing for packets larger than one GATT write.
- No Wi-Fi, HTTP, WebSocket, Firebase or cloud relay.

## Mesh
Controlled flooding is used for the first release. Every packet has a UUID, origin/sender IDs, destination, priority, TTL and hop count. Receivers authenticate/decrypt, apply replay/dedup/safety policy, persist before forwarding, and decrement TTL/increment hop count.

## Persistence
Room stores messages/queue, peers, event audit records and voice assets. Queue is bounded and expired items are pruned.

## Security
Device identity is a pseudonymous UUID plus an EC signing/agreement key in Android Keystore. HELLO packets carry the public key and an ECDSA signature. Peer sessions derive an AES key with ECDH; application payloads use AES-GCM. Message IDs provide replay/dedup protection.

## Emergency
SOS is priority 100, captures local GNSS when available, stores sender name/location/voice reference, relays with TTL, and triggers five local siren/vibration pulses.

## Voice
AAC/MP4 is used as the Android-native compressed fallback. Audio is split into bounded chunks, transferred as VOICE_META/VOICE_CHUNK packets and reassembled into a local file.


## Application navigation and integration (Stage 10)

The Compose shell exposes Home, Chats, Nearby, SOS, AI Voice, Reports and Profile. Events and local safety review are nested under Profile. The Home screen derives peer/conversation/message/sync indicators from live mesh, Room and cloud dashboard state. The Nearby screen lists only currently authenticated BLE peers and plots only real shared GPS fixes.

SOS remains a local-first path: user-selected category/severity are encoded in the existing SOS text payload, location is included only after explicit opt-in, and the local alarm starts before a bounded GPS lookup or BLE retry. The cloud outbox is a follow-on operation and is not part of local SOS delivery.

`MeshEngine` remains the BLE/mesh service owner; Room remains the local source of truth; provider clients remain separate; `CloudSyncCoordinator`/WorkManager own online synchronization. See `INTEGRATED_APPLICATION.md` for the screen-by-screen integration map and verification limits.


## Final verification corrections

The foreground service retains one `MeshEngine.state` collector across sticky start redelivery and cancels it when the service stops or is destroyed. Outgoing SOS status is distinct from delivery confirmation: `FORWARDED` means a next-hop GATT write was accepted, `QUEUED` means no eligible authenticated peer accepted it yet, and `DELIVERED` is set only after the matching ACK reaches the origin.
