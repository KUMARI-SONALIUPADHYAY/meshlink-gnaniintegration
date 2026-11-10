# MeshLink Protocol

Wire packets use a versioned compact binary header, not JSON. The BLE transport has a second binary frame layer for fragmentation.

Packet types: NODE_BEACON, HELLO, CAPABILITIES, ROUTE_SUMMARY, TEXT, VOICE_META, VOICE_CHUNK, SOS, ALERT, LOCATION, ACK, RECEIPT, CANCEL.

Priority: SOS 100, urgent alert 80, location 50, text 30, voice 10.

The first relay release uses controlled flooding. Duplicate message IDs are rejected after optional ACK, TTL is decremented on every relay and hop count incremented. Packets expire and remain in the Room queue for store-and-forward.

HELLO is the only intentionally unauthenticated transport packet; its payload is self-authenticating through an ECDSA signature over the node ID and public key. Subsequent payloads require an authenticated AES-GCM session.
