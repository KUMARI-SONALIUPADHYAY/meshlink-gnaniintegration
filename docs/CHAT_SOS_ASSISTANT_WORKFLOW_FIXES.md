# MeshLink 2.0 — Chat, SOS and Emergency Assistant Workflow Fixes

## Changes in this revision

### Chat composer
- The global SOS floating action button is hidden while the Chats destination is active. It previously occupied the same lower-right area as the group/personal composer and could visually cover the send affordance on small screens.
- The composer send control is now a filled icon button and is disabled while the input is empty.
- Group messages continue to use the broadcast route; personal messages use the selected authenticated peer's node ID.
- A locally queued message is not represented as delivered. The message bubble retains its queued/forwarded state and retry action.

### SOS recipient selection
- The SOS form now lets the sender choose all authenticated peers (group SOS) or one authenticated peer (personal SOS).
- The selected destination is passed to `MeshEngine.sendSos`; `null` destination means group broadcast, and a selected node ID means a personal SOS.
- The confirmation dialog repeats the recipient choice and warns when no authenticated peer is connected. Local alarm and local queue behavior remain independent of delivery.
- SOS delivery is not claimed until the normal mesh ACK path confirms it.

### Bluetooth enable flow
- When the user enables MeshLink while Android Bluetooth is off, the app now invokes Android's system Bluetooth-enable prompt after the required BLE permissions are granted.
- If the user declines, the app reports that Bluetooth remains off rather than silently starting a mesh service that cannot scan or advertise.
- This does not bypass Android permissions or enable Bluetooth without user consent.

### Emergency voice assistant
- Selecting an English or Hindi phrase now fills the response editor only; it no longer silently writes to the clipboard.
- Phrasebook items have direct `Send EN to group` / `Send HI to group` actions.
- Reviewed transcripts, extracted analysis, translations and the composed response have direct `Send to Mesh group` actions. External sharing remains separate and optional.
- The quick guide now includes medical, road accident, fire/smoke, flood/severe weather, violence/personal safety, earthquake, lost/separated and other emergency scenarios.
- India guidance describes 112 as the unified emergency number, rather than presenting it as a medical-only number. The guide gives scenario-specific first steps and does not imply that MeshLink itself dispatches public emergency services.

## BLE reference comparison

The older `MeshLink_v4.7_SELF_HEALING_CONNECTION_POLICY` source archive was compared with this project. `BleScanner.kt`, `BleAdvertiser.kt`, `GattClient.kt`, and the connection-selection/handshake path in `MeshEngine.kt` are source-identical to that older reference. The newer project adds BLE lifecycle/status handling and authenticated-peer UI state. This source comparison is not a substitute for physical-device verification.

## Verification and limitations

- Kotlin delimiter scan and targeted source checks: performed.
- Android XML parsing and ZIP integrity: performed.
- Full Gradle/Kotlin Android compilation: not performed; Gradle 9.6.0 could not be downloaded in the isolated environment (`UnknownHostException: services.gradle.org`).
- Android SDK build, APK install, two-phone GATT connection, SOS ACK, and three-phone relay: not physically tested in this environment.
- Group and personal send actions are wired to the existing mesh broadcast/direct-destination APIs. Actual delivery still requires both phones to have Bluetooth enabled, Nearby Devices permissions granted, MeshLink running, and a successful authenticated BLE session.
