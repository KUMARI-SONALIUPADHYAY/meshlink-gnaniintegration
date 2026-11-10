# Security

- Random UUID device identity; no phone-number identity.
- EC key material is generated in Android Keystore.
- ECDSA authenticates the HELLO public key.
- ECDH derives a per-peer session secret.
- AES-GCM encrypts application payloads.
- Packet IDs are used for replay/deduplication.
- TTL, payload length, protocol version and packet type are validated.
- Rate limits and deterministic safety rules operate locally.
- Sensitive message/audio data is never placed in BLE advertisements.
- Event log records authentication failures, relays, drops, safety holds and voice reassembly.
