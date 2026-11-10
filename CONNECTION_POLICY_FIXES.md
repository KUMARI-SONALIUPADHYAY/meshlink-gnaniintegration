# MeshLink connection-policy repair (v3.1 source)

## What changed

1. **Retry scheduler:** disconnected peers get bounded exponential retry delays (1, 2, 4, 8, 16, then 30 seconds). The scheduler revisits recently seen peers every maintenance cycle, so reconnecting does not depend only on receiving another scan callback.
2. **Duplicate connection prevention:** the deterministic node-ID initiator rule is retained, and `putIfAbsent` prevents overlapping scan callbacks from creating two client objects for the same BLE address.
3. **Stale callback protection:** a disconnect callback from an older GATT client removes the map entry only if that exact client is still registered. A late callback can no longer accidentally remove a newer connection attempt.
4. **GATT callback protection:** late callbacks from a closed connection are ignored; connection readiness is reset on disconnect. This prevents a timed-out connection from being treated as active again.
5. **Serialized setup:** service discovery now waits for MTU negotiation callback where available, with a bounded fallback for devices that do not return the callback.
6. **Server-ready advertising:** BLE advertising/scanning starts only after Android reports that the GATT service was successfully registered. Previously the app advertised immediately after `addService()` returned, although service registration is asynchronous.
7. **Authenticated peer record:** when a valid signed HELLO arrives, the engine creates/updates the peer record even if the scanner record has not arrived yet. This addresses messages being received while the authenticated node list remained empty.
8. **Version metadata:** Android versionCode increased to 8 and versionName to 3.1.

## Connection policy

- Both phones advertise their GATT server and scan for MeshLink discovery beacons.
- A deterministic node-ID comparison selects one initiating side per pair to reduce simultaneous-connect races. If a peer remains visible but is still unconnected after a 15-second grace period, the other side can take over initiation; it first checks that it is not already accepting a connection from that address.
- Outgoing central connections are capped at seven per phone as a resource budget. RSSI-sorted recent peers are retried first when capacity becomes available. The fallback initiator helps avoid stranding a peer when the preferred initiator has exhausted its outgoing slots.
- Failed links use exponential backoff rather than immediate rapid retries.
- Mesh routing forwards to authenticated peers. A larger mesh should be understood as a connected relay graph; BLE and Android impose practical concurrent-link limits, so a guarantee that every phone has a direct physical link to every other phone is not realistic at arbitrary scale.

## Validation status

- ZIP integrity and source-level consistency checks were run after edits.
- A Gradle build was **not** completed in this environment: the wrapper attempted to download Gradle 9.6.0 and the environment could not resolve `services.gradle.org`.
- No physical multi-phone connection test was performed here. Please do not treat this source ZIP as a verified APK. Build it in Android Studio and test with at least three phones, including disconnect/reconnect and message relay.
