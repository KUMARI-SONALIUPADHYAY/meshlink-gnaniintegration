package com.meshlink.app.core.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.annotation.RequiresPermission
import com.meshlink.app.core.bluetooth.*
import com.meshlink.app.core.emergency.EmergencyManager
import com.meshlink.app.core.location.LocationProvider
import com.meshlink.app.core.protocol.*
import com.meshlink.app.core.safety.SafetyDecision
import com.meshlink.app.core.safety.SafetyEngine
import com.meshlink.app.core.security.*
import com.meshlink.app.core.voice.VoiceChunk
import com.meshlink.app.core.voice.VoiceTransfer
import com.meshlink.app.core.voice.VoiceRecorder
import com.meshlink.app.core.voice.VoiceReassembler
import com.meshlink.app.data.database.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import android.util.Base64
import java.security.Signature
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class SosDisplay(
    val messageId:String,
    val sender:String,
    val message:String,
    val location:String,
    val hasVoice:Boolean,
    val timestamp:Long,
    val voicePath:String? = null,
    val reviewStatus:String = "PENDING",
    /** GATT forwarding is not end-to-end delivery; only an ACK sets DELIVERED. */
    val deliveryStatus:String = "PENDING",
    val local:Boolean = false,
    val latitude:Double? = null,
    val longitude:Double? = null,
    val accuracyMeters:Float? = null
)

data class PeerLocationDisplay(
    val nodeId:String,
    val name:String,
    val latitude:Double,
    val longitude:Double,
    val accuracyMeters:Float,
    val timestamp:Long
)
data class AlertDisplay(
    val sender:String,
    val text:String,
    val timestamp:Long,
    val local:Boolean
)
data class MeshUiState(
    val localName:String="Mesh Node",
    val localAvatarIndex:Int=0,
    val active:Boolean=false,
    val status:String="Mesh disabled",
    val bleStatus:String="Bluetooth not started",
    /** Recently observed BLE advertisements; discovery alone is not authentication. */
    val discoveredPeers:List<MeshPeer> = emptyList(),
    /** Authenticated, currently connected mesh peers. */
    val peers:List<MeshPeer> = emptyList(),
    val delivered:Int=0,
    val queued:Int=0,
    val lastRoute:String="",
    val lastSos:SosDisplay?=null,
    val lastAlert:AlertDisplay?=null,
    val lastVoicePath:String?=null,
    val lastLocationText:String?=null,
    val localLatitude:Double?=null,
    val localLongitude:Double?=null,
    val peerLocations:Map<String,PeerLocationDisplay> = emptyMap()
)

@Singleton
class MeshEngine @Inject constructor(@ApplicationContext private val context:Context){
    private val db=MeshDatabase.getDatabase(context)
    private val identity=DeviceIdentity(context)
    private val crypto=CryptoManager(identity)
    private val security=PacketSecurity(crypto)
    private val replay=ReplayProtection()
    private val safety=SafetyEngine()
    private val location=LocationProvider(context)
    private val emergency=EmergencyManager(context)
    private val voiceRecorder=VoiceRecorder(context)
    private val voiceReassembler=VoiceReassembler(context)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val clients=ConcurrentHashMap<String,GattClient>()
    // Per-address exponential backoff prevents scan callbacks from creating a rapid reconnect storm.
    private val connectionFailures=ConcurrentHashMap<String,Int>()
    private val nextConnectionAttemptAt=ConcurrentHashMap<String,Long>()
    private val peerFirstSeenAt=ConcurrentHashMap<String,Long>()
    private val peers=ConcurrentHashMap<String,MeshPeer>()
    private val deviceToNode=ConcurrentHashMap<String,UUID>()
    private val nodeToDevice=ConcurrentHashMap<UUID,BluetoothDevice>()
    private val reassemblers=ConcurrentHashMap<String,FrameReassembler>()
    private val _state=MutableStateFlow(MeshUiState(localName = identity.displayName, localAvatarIndex = identity.avatarIndex))
    val state:StateFlow<MeshUiState> = _state.asStateFlow()
    val messages=db.messageDao().observeAll()
    val events=db.eventDao().observe()
    val safetyMessages=db.messageDao().observeSafety()
    val peerFlow=db.peerDao().observeAll()
    private var server:GattServer?=null
    private var ble:BleManager?=null
    private var running=false
    private var maintenanceJob: Job? = null

    @SuppressLint("MissingPermission")
    fun start(){
        if(running)return
        running=true
        server=GattServer(context,::onServerData){status ->
            setBleStatus(status)
            // Do not advertise a connectable peer until its GATT service is registered.
            if (status == "GATT server active") startBleTransport()
        }
        server?.start()
        _state.update{it.copy(active=true,status="Starting Bluetooth mesh…",bleStatus="Waiting for GATT server")}
        maintenanceJob = scope.launch { maintenance() }
        log("SERVICE","Mesh mode enabled",null,null)
    }

    @SuppressLint("MissingPermission")
    private fun startBleTransport() {
        if (!running || ble != null) return
        val manager = BleManager(context, identity.nodeId.toString(), identity.displayName)
        manager.onPeerDiscovered = { peer -> onPeer(peer) }
        manager.onStatusChanged = { status -> setBleStatus(status) }
        ble = manager
        manager.start()
    }

    fun stop(){
        if(!running)return
        running=false
        maintenanceJob?.cancel()
        maintenanceJob = null
        ble?.stop()
        ble = null
        server?.stop()
        server = null
        clients.values.forEach { it.disconnect() }
        clients.clear()
        connectionFailures.clear()
        nextConnectionAttemptAt.clear()
        peers.clear()
        deviceToNode.clear()
        nodeToDevice.clear()
        reassemblers.clear()
        _state.update { it.copy(active=false,status="Mesh disabled",bleStatus="Stopped", discoveredPeers=emptyList(), peers=emptyList()) }
        log("SERVICE","Mesh mode disabled",null,null)
    }

    fun identity() = identity

    fun setDisplayName(name: String) {
        identity.setDisplayName(name)
        _state.update { it.copy(localName = identity.displayName) }
        if (running) {
            // Do not tear down the live BLE scanner/advertiser just to rename a
            // profile. The MeshLink display name lives in the authenticated HELLO,
            // so we can propagate it without interrupting an active GATT session.
            scope.launch {
                val delays = longArrayOf(100L, 500L, 1500L)
                for (delayMs in delays) {
                    delay(delayMs)
                    broadcastHelloToConnectedPeers()
                }
            }
        }
        log("PROFILE", "Display name updated to ${identity.displayName}", identity.nodeId.toString(), null)
    }

    fun setAvatarIndex(index: Int) {
        identity.setAvatarIndex(index)
        _state.update { it.copy(localAvatarIndex = identity.avatarIndex) }
        log("PROFILE", "Avatar updated", identity.nodeId.toString(), null)
    }

    fun startVoiceRecording(): Boolean = voiceRecorder.start() != null

    fun stopVoiceRecording(): java.io.File? {
        val file = voiceRecorder.stop()
        if (file != null && file.exists() && file.length() > 0L) {
            _state.update { it.copy(lastVoicePath = file.absolutePath) }
            log("VOICE", "Local voice recording ready", identity.nodeId.toString(), null)
            return file
        }
        return null
    }

    /** Persist a local or imported recording under the stable logical voice-message ID. */
    suspend fun registerLocalVoiceAsset(messageId: String, file: java.io.File, durationMs: Long = 0L) {
        require(file.isFile && file.length() > 0L) { "Voice recording is missing or empty." }
        db.voiceAssetDao().insert(
            VoiceAssetEntity(messageId, file.extension.uppercase(Locale.ROOT), durationMs, file.absolutePath, 1, 1)
        )
    }

    /** Store Prisma output separately from the source recording; the audio path is preserved. */
    suspend fun saveVoiceTranscription(messageId: String, transcript: String, languageCode: String) {
        require(messageId.isNotBlank()) { "A voice message ID is required." }
        db.voiceTranscriptionDao().upsert(
            VoiceTranscriptionEntity(messageId, transcript, languageCode, "Gnani Prisma v2.5", System.currentTimeMillis())
        )
        log("VOICE", "Prisma transcription saved for voice message", identity.nodeId.toString(), messageId)
    }

    /** Save validated Evon analysis without replacing the user's original transcription. */
    suspend fun saveEmergencyAnalysis(messageId: String, analysis: com.meshlink.app.core.ai.EvonEmergencyAnalysis) {
        require(messageId.isNotBlank())
        db.emergencyCoordinationDao().saveAnalysis(
            EmergencyAnalysisEntity(
                messageId = messageId,
                emergencyType = analysis.emergencyType,
                severity = analysis.severity,
                locationMentioned = analysis.locationMentioned,
                peopleAffected = analysis.peopleAffected,
                immediateAssistance = analysis.immediateAssistance,
                instructionsJson = org.json.JSONArray(analysis.instructions).toString(),
                warningsJson = org.json.JSONArray(analysis.warnings).toString(),
                suggestedResponse = analysis.suggestedResponse,
                rawJson = analysis.rawJson,
                createdAt = System.currentTimeMillis()
            )
        )
        log("AI", "Emergency analysis saved for review", identity.nodeId.toString(), messageId)
    }

    suspend fun saveEmergencyTranslation(messageId: String, result: com.meshlink.app.core.ai.TranslationResult, contentKind: String) {
        require(messageId.isNotBlank())
        db.emergencyCoordinationDao().saveTranslation(
            TranslationEntity(
                id = "$messageId:${result.targetLanguage}:$contentKind", messageId = messageId,
                sourceText = result.sourceText, translatedText = result.translatedText,
                sourceLanguage = result.sourceLanguage, targetLanguage = result.targetLanguage,
                provider = result.provider, contentKind = contentKind, createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun queueAiWorkflow(messageId: String, audioFile: java.io.File, sourceLanguage: String, targetLanguage: String, voice: String, stage: String, error: String?, sourceText: String = "") {
        db.emergencyCoordinationDao().removeWorkflowStage(messageId, stage)
        db.emergencyCoordinationDao().enqueueWorkflow(
            AiWorkflowQueueEntity("$messageId:$stage", messageId, audioFile.absolutePath, sourceText, sourceLanguage, targetLanguage, voice,
                stage, "PENDING", 1, error?.take(240), System.currentTimeMillis())
        )
        log("AI", "Voice workflow queued for retry", identity.nodeId.toString(), messageId)
    }

    suspend fun clearAiWorkflow(messageId: String, stage: String) {
        db.emergencyCoordinationDao().completeWorkflowStage(messageId, stage)
    }

    suspend fun clearAiWorkflowJob(jobId: String) {
        db.emergencyCoordinationDao().completeWorkflow(jobId)
    }

    suspend fun pendingAiWorkflows(): List<AiWorkflowQueueEntity> = db.emergencyCoordinationDao().pendingWorkflows()

    suspend fun failAiWorkflow(jobId: String, error: String?) {
        db.emergencyCoordinationDao().failWorkflow(jobId, error?.take(240), System.currentTimeMillis())
    }

    suspend fun advanceAiWorkflow(jobId: String, stage: String, sourceText: String, targetLanguage: String, voice: String, error: String? = null) {
        db.emergencyCoordinationDao().updateWorkflow(jobId, stage, sourceText, targetLanguage, voice, error?.take(240), System.currentTimeMillis())
    }

    /** Persist a Timbre response against the same logical emergency-message ID. */
    suspend fun saveGeneratedSpeech(messageId: String, file: java.io.File, languageCode: String, voice: String, model: String) {
        require(messageId.isNotBlank()) { "A message ID is required for generated speech." }
        require(file.isFile && file.length() > 0L) { "Generated speech file is missing or empty." }
        db.generatedSpeechDao().upsert(
            GeneratedSpeechEntity(messageId, file.absolutePath, languageCode, voice, model, System.currentTimeMillis())
        )
        log("VOICE", "Timbre audio saved for emergency message", identity.nodeId.toString(), messageId)
    }

    suspend fun sendText(destination:UUID,text:String){
        if(text.isBlank())return
        val id=UUID.randomUUID()
        val packet=basePacket(id,destination,PacketType.TEXT,30,text.toByteArray())
        persistOutgoing(packet,text)
        route(packet)
    }

    /** Sends a mesh-wide text packet. Controlled flooding + deduplication relays it across the mesh. */
    suspend fun broadcastText(text:String){
        if(text.isBlank()) return
        val packet=basePacket(UUID.randomUUID(),null,PacketType.TEXT,30,text.toByteArray(),ttl=8,expiry=24*60*60_000L)
        persistOutgoing(packet,text)
        route(packet)
    }

    suspend fun sendLocation(destination:UUID?=null){
        val loc=location.current()
        val payload=if(loc==null)ByteArray(0) else ByteBuffer.allocate(8+8+4+8).putDouble(loc.latitude).putDouble(loc.longitude).putFloat(loc.accuracyMeters).putLong(loc.timestamp).array()
        val p=basePacket(UUID.randomUUID(),destination,PacketType.LOCATION,50,payload)
        persistOutgoing(p,null);route(p)
        _state.update {
            it.copy(
                lastLocationText = if (loc == null) "Location unavailable offline" else "%.5f, %.5f • ±%.0fm".format(loc.latitude, loc.longitude, loc.accuracyMeters),
                localLatitude = loc?.latitude,
                localLongitude = loc?.longitude
            )
        }
    }

    suspend fun sendAlert(destination:UUID?,text:String){
        val clean = text.trim()
        if (clean.isBlank()) return
        val p=basePacket(UUID.randomUUID(),destination,PacketType.ALERT,80,clean.toByteArray(),ttl=8,expiry=24*60*60_000L)
        persistOutgoing(p,clean)
        val sent = route(p)
        val queued = !sent
        val shown = if (queued) "$clean\n[queued until an authenticated mesh peer is available]" else clean
        _state.update { it.copy(lastAlert = AlertDisplay(identity.displayName, shown, System.currentTimeMillis(), true)) }
        log("ALERT", if (queued) "Alert queued: $clean" else "Alert sent: $clean", identity.nodeId.toString(), p.messageId.toString())
    }

    suspend fun reviewSafety(messageId: String, decision: SafetyDecision) {
        val existing = db.messageDao().byId(messageId) ?: return
        val status = when (decision) {
            SafetyDecision.PASS -> "SAFE"
            SafetyDecision.HOLD -> "HELD"
            SafetyDecision.BLOCK -> "BLOCKED"
        }
        db.messageDao().updateStatus(messageId, status, "Reviewed locally")
        log("SAFETY", "Manual review: $status", existing.originNodeId, messageId)
        if (decision != SafetyDecision.PASS) return

        val payload = runCatching {
            Base64.decode(existing.payloadReference ?: "", Base64.NO_WRAP)
        }.getOrNull() ?: return
        val packet = runCatching {
            MeshPacket(
                1, UUID.fromString(existing.messageId), UUID.fromString(existing.senderNodeId),
                UUID.fromString(existing.originNodeId), existing.receiverNodeId.takeIf { it != "*" }?.let(UUID::fromString),
                PacketType.valueOf(existing.type), existing.priority, existing.createdAt, existing.ttl, existing.hopCount, 0, payload
            )
        }.getOrNull() ?: return

        val local = packet.destinationNodeId == null || packet.destinationNodeId == identity.nodeId
        if (local) {
            db.messageDao().updateStatus(messageId, "DELIVERED", "Passed local safety review")
            when (packet.type) {
                PacketType.SOS -> handleIncomingSos(packet)
                PacketType.ALERT -> handleIncomingAlert(packet)
                PacketType.LOCATION -> handleIncomingLocation(packet)
                PacketType.VOICE_CHUNK -> handleIncomingVoice(packet)
                else -> Unit
            }
        }
        if (packet.ttl > 0) route(packet)
        log("SAFETY", "Passed review and released to mesh", packet.originNodeId.toString(), messageId)
    }

    suspend fun reviewSos(messageId:String, decision:String){
        val normalized = decision.uppercase()
        if (normalized !in setOf("REVIEW", "PASS", "REJECT")) return
        val existing = db.messageDao().byId(messageId) ?: return
        if (existing.type != PacketType.SOS.name) return
        if (normalized == "REVIEW") {
            db.messageDao().updateStatus(messageId, "SOS_REVIEWING", "SOS opened for review")
            _state.update { state -> state.copy(lastSos = state.lastSos?.takeIf { it.messageId == messageId }?.copy(reviewStatus = "REVIEWING") ?: state.lastSos) }
            log("SOS", "SOS opened for review", existing.originNodeId, messageId)
            return
        }
        db.messageDao().updateStatus(messageId, "SOS_${normalized}", "SOS reviewed locally")
        val destination = existing.originNodeId.toUuidOrNull() ?: return
        val payload = ByteBuffer.allocate(17)
            .putLong(UUID.fromString(messageId).mostSignificantBits)
            .putLong(UUID.fromString(messageId).leastSignificantBits)
            .put(if (normalized == "PASS") 1 else 0)
            .array()
        val packet = basePacket(UUID.randomUUID(), destination, PacketType.SOS_REVIEW, 100, payload, ttl = 8, expiry = 10 * 60_000L)
        persistOutgoing(packet, "SOS ${normalized}")
        route(packet)
        log("SOS", "SOS $normalized by ${identity.displayName}", existing.originNodeId, messageId)
        _state.update { state -> state.copy(lastSos = state.lastSos?.takeIf { it.messageId == messageId }?.copy(reviewStatus = normalized) ?: state.lastSos) }
    }

    suspend fun sendSos(destination:UUID?=null, voiceFile:java.io.File?=null, message:String="", voiceMessageId:UUID?=null, shareLocation:Boolean=false): UUID{
        // Start the local alarm immediately; GPS lookup and BLE retries must not delay it.
        scope.launch { emergency.triggerLocal() }
        val loc=if (shareLocation) withTimeoutOrNull(1_200L) { location.current() } else null
        val locBytes=if(loc==null)ByteArray(0) else ByteBuffer.allocate(8+8+4+8).putDouble(loc.latitude).putDouble(loc.longitude).putFloat(loc.accuracyMeters).putLong(loc.timestamp).array()
        val name=identity.displayName.toByteArray()
        val cleanMessage = message.trim().take(500)
        val messageBytes = cleanMessage.toByteArray()
        val payload=ByteBuffer.allocate(2+name.size+1+locBytes.size+1+2+messageBytes.size)
            .putShort(name.size.toShort()).put(name)
            .put(if(locBytes.isNotEmpty())1 else 0).put(locBytes)
            .put(if(voiceFile!=null)1 else 0)
            .putShort(messageBytes.size.toShort()).put(messageBytes).array()
        val p=basePacket(voiceMessageId ?: UUID.randomUUID(),destination,PacketType.SOS,100,payload,ttl=8,expiry=10*60_000)
        persistOutgoing(p,"SOS")

        // SOS uses the same authenticated BLE transport as text, but it is critical
        // enough that a transient GATT write/discovery race gets a few bounded retries.
        // The packet is still stored locally, so a later peer connection can flush it.
        var sent = route(p)
        if (!sent) {
            repeat(3) { attempt ->
                delay(350L * (attempt + 1))
                if (!sent) sent = route(p)
            }
        }
        val deliveryStatus = if (sent) "FORWARDED" else "QUEUED"
        _state.update {
            it.copy(
                lastSos = SosDisplay(p.messageId.toString(), identity.displayName, cleanMessage.ifBlank { "Emergency assistance requested" },
                    if (loc == null) "Location unavailable offline" else "%.5f, %.5f • ±%.0fm".format(loc.latitude, loc.longitude, loc.accuracyMeters),
                    voiceFile != null, System.currentTimeMillis(), voiceFile?.absolutePath,
                    deliveryStatus = deliveryStatus, local = true,
                    latitude = loc?.latitude, longitude = loc?.longitude, accuracyMeters = loc?.accuracyMeters),
                lastLocationText = if (loc == null) "Location unavailable offline" else "%.5f, %.5f • ±%.0fm".format(loc.latitude, loc.longitude, loc.accuracyMeters),
                localLatitude = loc?.latitude, localLongitude = loc?.longitude
            )
        }
        log("SOS", if (sent) "SOS sent to authenticated mesh peer(s)" else "SOS queued; no authenticated peer accepted the packet", identity.nodeId.toString(), p.messageId.toString())
        voiceFile?.let{sendVoice(p.messageId,it,destination)}
        return p.messageId
    }

    suspend fun sendVoice(parentId:UUID,file:java.io.File,destination:UUID?=null){
        val bytes=file.readBytes()
        val duration=voiceRecorder.lastDurationMs
        db.voiceAssetDao().insert(
            VoiceAssetEntity(parentId.toString(), file.extension.uppercase(Locale.ROOT), duration, file.absolutePath, 1, 1)
        )
        val chunks=VoiceTransfer.chunks(parentId,bytes,duration)
        if (db.messageDao().byId(parentId.toString()) == null) {
            db.messageDao().insertMessage(
                MessageEntity(parentId.toString(), identity.nodeId.toString(), identity.nodeId.toString(),
                    destination?.toString() ?: "*", identity.displayName, PacketType.VOICE_CHUNK.name, 10, "QUEUED",
                    System.currentTimeMillis(), System.currentTimeMillis() + 2 * 60_000L, 5, 0, file.absolutePath, "Voice message", false, false)
            )
        }
        val meta=ByteBuffer.allocate(16+2+8+1).putLong(parentId.mostSignificantBits).putLong(parentId.leastSignificantBits)
            .putShort(chunks.size.toShort()).putLong(duration).put(1).array()
        val metaPacket=basePacket(UUID.randomUUID(),destination,PacketType.VOICE_META,10,meta,ttl=5,expiry=2*60_000)
        persistOutgoing(metaPacket,null);route(metaPacket)
        for(c in chunks){
            val p=basePacket(UUID.randomUUID(),destination,PacketType.VOICE_CHUNK,10,VoiceTransfer.encode(c),ttl=5,expiry=2*60_000)
            persistOutgoing(p,null)
            route(p)
            yield()
        }
    }

    private fun basePacket(id:UUID,destination:UUID?,type:PacketType,priority:Int,payload:ByteArray,ttl:Int=6,expiry:Long=24*60*60_000):MeshPacket=
        MeshPacket(1,id,identity.nodeId,identity.nodeId,destination,type,priority,System.currentTimeMillis(),ttl,0,0,payload)

    private fun expiryFor(type: PacketType): Long = when (type) {
        PacketType.SOS, PacketType.SOS_REVIEW -> 10 * 60_000L
        PacketType.VOICE_META, PacketType.VOICE_CHUNK -> 2 * 60_000L
        PacketType.ACK, PacketType.HELLO, PacketType.CAPABILITIES -> 5 * 60_000L
        else -> 24 * 60 * 60_000L
    }

    private suspend fun persistOutgoing(p: MeshPacket, text: String?) {
        while (db.messageDao().queueSize() >= 200) db.messageDao().deleteLowestPriority()
        db.messageDao().insertMessage(
            MessageEntity(
                p.messageId.toString(), p.originNodeId.toString(), p.senderNodeId.toString(),
                p.destinationNodeId?.toString() ?: "*", identity.displayName, p.type.name, p.priority, "QUEUED",
                p.createdAt, p.createdAt + expiryFor(p.type), p.ttl, p.hopCount,
                Base64.encodeToString(p.payload, Base64.NO_WRAP), text, false, false
            )
        )
    }

    private suspend fun persistRelay(p: MeshPacket, text: String?) {
        val existing = db.messageDao().byId(p.messageId.toString())
        val expiresAt = p.createdAt + expiryFor(p.type)
        if (existing == null) {
            while (db.messageDao().queueSize() >= 200) db.messageDao().deleteLowestPriority()
            db.messageDao().insertMessage(
                MessageEntity(
                    p.messageId.toString(), p.originNodeId.toString(), p.senderNodeId.toString(),
                    p.destinationNodeId?.toString() ?: "*", resolveName(p.originNodeId), p.type.name, p.priority, "QUEUED",
                    p.createdAt, expiresAt, p.ttl, p.hopCount,
                    Base64.encodeToString(p.payload, Base64.NO_WRAP), text, false, false
                )
            )
        } else if (existing.status != "DELIVERED") {
            db.messageDao().updateRoutingState(
                p.messageId.toString(), p.senderNodeId.toString(), "QUEUED", p.ttl, p.hopCount,
                Base64.encodeToString(p.payload, Base64.NO_WRAP), text, expiresAt
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun onPeer(peer:MeshPeer){
        if (peer.nodeId.equals(identity.nodeId.toString(), true)) return
        val now = System.currentTimeMillis()
        val previous = peers[peer.address]
        // Advertisements identify a node but do not authenticate it. Preserve a real
        // profile name once HELLO has authenticated the identity.
        val stableName = if (peer.displayName == "Authenticating…" && previous != null &&
            previous.displayName.isNotBlank() && previous.displayName != "Authenticating…") {
            previous.displayName
        } else peer.displayName
        peerFirstSeenAt.putIfAbsent(peer.address, now)
        val observed = peer.copy(displayName = stableName, lastSeen = now)
        peers[peer.address] = observed
        _state.update { current ->
            val visible = peers.values
                .filter { now - it.lastSeen <= 20_000L }
                .groupBy { it.nodeId.lowercase(Locale.ROOT) }
                .values.mapNotNull { group -> group.maxByOrNull { it.lastSeen } }
            current.copy(discoveredPeers = visible)
        }
        scope.launch {
            db.peerDao().insertPeer(PeerEntity(peer.nodeId, peer.address, stableName, now,
                "DISCOVERED", peer.rssi, "TEXT,LOCATION,SOS,VOICE", "UNKNOWN"))
        }
        publishAuthenticatedPeers()
        attemptConnection(observed)
    }

    /**
     * A single deterministic initiator per pair avoids both phones racing to open
     * the same GATT link. The peer remains discoverable while waiting for a slot;
     * maintenance retries it later. Failed links use exponential backoff.
     */
    @SuppressLint("MissingPermission")
    private fun attemptConnection(peer: MeshPeer, fallbackInitiator: Boolean = false) {
        if (!running || peer.nodeId.equals(identity.nodeId.toString(), true)) return
        val preferredInitiator = identity.nodeId.toString().compareTo(peer.nodeId, ignoreCase = true) < 0
        // Preferred side normally initiates. If it is saturated or has repeatedly
        // failed, the other side may take over after a grace period so a crowded
        // low-ID node cannot strand peers merely because its outgoing slots are full.
        if (!preferredInitiator && !fallbackInitiator) return
        if (fallbackInitiator && System.currentTimeMillis() - (peerFirstSeenAt[peer.address] ?: System.currentTimeMillis()) < FALLBACK_INITIATOR_GRACE_MS) return
        // If this phone is already accepting an incoming link from that address,
        // do not open a second, redundant GATT connection to the same device.
        if (server?.hasConnection(peer.address) == true) return
        if (System.currentTimeMillis() - peer.lastSeen > 20_000L) return
        if (clients.containsKey(peer.address) || clients.size >= MAX_OUTGOING_GATT_LINKS) return
        if (System.currentTimeMillis() < (nextConnectionAttemptAt[peer.address] ?: 0L)) return

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = try { manager.adapter.getRemoteDevice(peer.address) } catch (_: Throwable) { null } ?: return
        val client = GattClient(context)
        // putIfAbsent prevents overlapping scan callbacks from opening duplicate GATT clients.
        if (clients.putIfAbsent(peer.address, client) != null) return

        client.onConnected = {
            if (clients[peer.address] === client) {
                connectionFailures.remove(peer.address)
                nextConnectionAttemptAt.remove(peer.address)
                scope.launch {
                    db.peerDao().insertPeer(PeerEntity(peer.nodeId, peer.address, peers[peer.address]?.displayName ?: peer.displayName,
                        System.currentTimeMillis(), "CONNECTED", peers[peer.address]?.rssi ?: peer.rssi,
                        "TEXT,LOCATION,SOS,VOICE", "UNKNOWN"))
                }
                sendHello(client)
            }
        }
        client.onDisconnected = {
            // A delayed callback from an old GATT instance must never remove a newer client.
            val removed = clients.remove(peer.address, client)
            if (removed && running) {
                val failures = ((connectionFailures[peer.address] ?: 0) + 1).coerceAtMost(6)
                connectionFailures[peer.address] = failures
                val delayMs = minOf(30_000L, 1_000L shl (failures - 1))
                nextConnectionAttemptAt[peer.address] = System.currentTimeMillis() + delayMs
                scope.launch {
                    db.peerDao().insertPeer(PeerEntity(peer.nodeId, peer.address, peers[peer.address]?.displayName ?: peer.displayName,
                        System.currentTimeMillis(), "STALE", peers[peer.address]?.rssi ?: peer.rssi,
                        "TEXT,LOCATION,SOS,VOICE", "UNKNOWN"))
                }
                setStatus("${peer.displayName} disconnected; retry in ${delayMs / 1000}s")
            }
        }
        client.onDataReceived = { raw -> onClientData(peer.address, raw) }
        client.onStatusChanged = { message -> setBleStatus("${peer.displayName}: $message") }
        client.connect(device)
    }

    private fun retryEligibleConnections() {
        if (!running || clients.size >= MAX_OUTGOING_GATT_LINKS) return
        val now = System.currentTimeMillis()
        peers.values
            .asSequence()
            .filter { now - it.lastSeen <= 20_000L }
            .sortedByDescending { it.rssi }
            .forEach { peer ->
                val preferred = identity.nodeId.toString().compareTo(peer.nodeId, ignoreCase = true) < 0
                val fallbackReady = !preferred &&
                    now - (peerFirstSeenAt[peer.address] ?: now) >= FALLBACK_INITIATOR_GRACE_MS
                if (clients.size < MAX_OUTGOING_GATT_LINKS &&
                    now >= (nextConnectionAttemptAt[peer.address] ?: 0L) &&
                    !clients.containsKey(peer.address) && (preferred || fallbackReady)) {
                    attemptConnection(peer, fallbackInitiator = !preferred && fallbackReady)
                }
            }
    }

    private fun sendToAddress(address:String, nodeId:UUID, data:ByteArray, device:BluetoothDevice? = null):Boolean {
        val client = clients[address]
        if (client != null) {
            return client.send(data)
        }
        val target = device ?: nodeToDevice[nodeId] ?: runCatching {
            val bm=context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter.getRemoteDevice(address)
        }.getOrNull()
        return target?.let { server?.sendToDevice(it, data) == true } ?: false
    }

    private fun sendHello(c:GattClient){
        c.send(security.encode(sendHelloPacket()))
    }

    @SuppressLint("MissingPermission")
    private fun broadcastHelloToConnectedPeers() {
        clients.values.toList().forEach { sendHello(it) }
        val hello = security.encode(sendHelloPacket())
        server?.connectedDevices()?.forEach { device ->
            runCatching { server?.sendToDevice(device, hello) }
        }
    }

    private fun sendHelloPacket(): MeshPacket {
        val sig=Signature.getInstance("SHA256withECDSA").apply{
            initSign(identity.keyPair().private)
            update(identity.nodeId.toString().toByteArray())
            update(identity.publicKeyEncoded())
        }.sign()
        val payload=HelloCodec.encode(identity.nodeId,identity.publicKeyEncoded(),sig,identity.displayName)
        return basePacket(UUID.randomUUID(),null,PacketType.HELLO,100,payload,ttl=1,expiry=60_000)
    }

    private fun onClientData(address:String,raw:ByteArray){
        val complete=reassemblers.getOrPut(address){FrameReassembler()}.accept(raw)?:return
        onIncoming(address,null,complete)
    }
    private fun onServerData(device:BluetoothDevice,raw:ByteArray){
        val complete=reassemblers.getOrPut(device.address){FrameReassembler()}.accept(raw)?:return
        onIncoming(device.address,device,complete)
    }

    @SuppressLint("MissingPermission")
    private fun onIncoming(address:String,device:BluetoothDevice?,raw:ByteArray){
        scope.launch { processIncoming(address,device,raw) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun processIncoming(address: String, device: BluetoothDevice?, raw: ByteArray) {
        val p = security.decode(raw) ?: run {
            log("DROP", "Packet authentication failed", null, null)
            return
        }
        val now = System.currentTimeMillis()
        if (p.protocolVersion.toInt() != 1 || p.ttl !in 0..12 || p.payload.size > 60_000 ||
            p.createdAt < now - 24 * 60 * 60_000L || p.createdAt > now + 5 * 60_000L) return

        if (p.type == PacketType.HELLO) {
            handleHello(address, device, p)
            return
        }

        if (!replay.accept(p.messageId)) {
            if (p.type != PacketType.ACK && p.destinationNodeId == identity.nodeId) {
                sendAckIfPossible(p, address, device)
            }
            return
        }

        if (p.type == PacketType.ACK) {
            val b = if (p.payload.size == 16) ByteBuffer.wrap(p.payload) else null
            val ackedId = b?.let { UUID(it.long, it.long) }
            if (p.destinationNodeId == identity.nodeId) {
                ackedId?.let { acknowledgedId ->
                    db.messageDao().markDelivered(acknowledgedId.toString())
                    _state.update { current ->
                        val sos = current.lastSos
                        if (sos?.messageId == acknowledgedId.toString()) {
                            current.copy(lastSos = sos.copy(deliveryStatus = "DELIVERED"))
                        } else current
                    }
                }
                log("ACK", "Delivery acknowledgement for $ackedId", p.senderNodeId.toString(), ackedId?.toString())
            } else if (p.ttl > 0) {
                forward(p, address, null)
            }
            return
        }

        val text = if (p.type == PacketType.TEXT || p.type == PacketType.ALERT) String(p.payload, Charsets.UTF_8) else null
        val safe = safety.evaluate(p, text)
        if (safe.decision == SafetyDecision.BLOCK || safe.decision == SafetyDecision.HOLD) {
            val safetyStatus = if (safe.decision == SafetyDecision.BLOCK) "BLOCKED" else "HELD"
            val existingSafety = db.messageDao().byId(p.messageId.toString())
            if (existingSafety == null) {
                while (db.messageDao().queueSize() >= 200) db.messageDao().deleteLowestPriority()
                db.messageDao().insertMessage(
                    MessageEntity(
                        p.messageId.toString(), p.originNodeId.toString(), p.senderNodeId.toString(),
                        p.destinationNodeId?.toString() ?: "*", resolveName(p.originNodeId), p.type.name,
                        p.priority, safetyStatus, p.createdAt, p.createdAt + expiryFor(p.type), p.ttl, p.hopCount,
                        Base64.encodeToString(p.payload, Base64.NO_WRAP), text ?: p.type.name,
                        false, p.destinationNodeId == null || p.destinationNodeId == identity.nodeId, safe.reason
                    )
                )
            } else {
                db.messageDao().updateStatus(p.messageId.toString(), safetyStatus, safe.reason)
            }
            log("SAFETY", "${safe.decision}: ${safe.reason} (risk=${"%.2f".format(Locale.US, safe.riskScore)})", p.senderNodeId.toString(), p.messageId.toString())
            return
        }

        val local = p.destinationNodeId == null || p.destinationNodeId == identity.nodeId
        val existing = db.messageDao().byId(p.messageId.toString())
        if (existing == null) {
            val displaySenderId = if (local) p.originNodeId else p.senderNodeId
            db.messageDao().insertMessage(
                MessageEntity(
                    p.messageId.toString(), p.originNodeId.toString(), displaySenderId.toString(),
                    p.destinationNodeId?.toString() ?: "*", resolveName(displaySenderId), p.type.name, p.priority,
                    if (local) "RECEIVED" else "QUEUED", p.createdAt, p.createdAt + expiryFor(p.type),
                    p.ttl, p.hopCount, null, text, local, local
                )
            )
        }
        log("RECEIVE", "${p.type} hop=${p.hopCount} ttl=${p.ttl}", p.senderNodeId.toString(), p.messageId.toString())

        if (local) {
            db.messageDao().updateStatus(p.messageId.toString(), "DELIVERED")
            when (p.type) {
                PacketType.SOS -> handleIncomingSos(p)
                PacketType.SOS_REVIEW -> handleIncomingSosReview(p)
                PacketType.ALERT -> handleIncomingAlert(p)
                PacketType.LOCATION -> handleIncomingLocation(p)
                PacketType.VOICE_CHUNK -> handleIncomingVoice(p)
                else -> Unit
            }
            sendAckIfPossible(p, address, device)
            _state.update { it.copy(lastRoute = "${p.originNodeId.toString().takeLast(4)} → ${identity.nodeId.toString().takeLast(4)} • ${p.hopCount} hops") }
            // Broadcast packets (destination == null) are delivered locally AND
            // relayed onward. Previously the local-delivery branch stopped here,
            // which meant SOS, ALERT and voice broadcasts could die at the first
            // hop instead of reaching the rest of the mesh.
            if (p.destinationNodeId == null && p.ttl > 0) {
                val next = p.copy(
                    senderNodeId = identity.nodeId,
                    ttl = max(0, p.ttl - 1),
                    hopCount = p.hopCount + 1
                )
                persistRelay(next, text)
                route(next, address)
            }
        } else if (p.ttl > 0) {
            val next = p.copy(
                senderNodeId = identity.nodeId,
                ttl = max(0, p.ttl - 1),
                hopCount = p.hopCount + 1
            )
            persistRelay(next, text)
            route(next, address)
        } else {
            db.messageDao().updateStatus(p.messageId.toString(), "EXPIRED", "TTL exhausted")
        }
    }

    private suspend fun handleIncomingSos(p: MeshPacket) {
        val sos = runCatching {
            val b = ByteBuffer.wrap(p.payload)
            val nameLength = b.short.toInt() and 0xFFFF
            if (nameLength > b.remaining()) error("invalid SOS name")
            val nameBytes = ByteArray(nameLength).also { b.get(it) }
            val name = nameBytes.toString(Charsets.UTF_8)
            val hasLocation = b.get().toInt() != 0
            var latitude: Double? = null
            var longitude: Double? = null
            var accuracyMeters: Float? = null
            val locationText = if (hasLocation && b.remaining() >= 28) {
                latitude = b.double
                longitude = b.double
                accuracyMeters = b.float
                b.long
                "%.5f, %.5f • ±%.0fm".format(latitude, longitude, accuracyMeters)
            } else "Location unavailable offline"
            val hasVoice = b.get().toInt() != 0
            val message = if (b.remaining() >= 2) {
                val n = (b.short.toInt() and 0xFFFF).coerceAtMost(b.remaining())
                ByteArray(n).also { b.get(it) }.toString(Charsets.UTF_8).trim()
            } else ""
            run {
                val attachedVoice = if (hasVoice) db.voiceAssetDao().byId(p.messageId.toString())?.localPath else null
                SosDisplay(
                    p.messageId.toString(), name, message.ifBlank { "Emergency assistance requested" },
                    locationText, hasVoice, p.createdAt, attachedVoice, "PENDING", "RECEIVED",
                    local = false, latitude = latitude, longitude = longitude, accuracyMeters = accuracyMeters
                )
            }
        }.getOrNull()
        if (sos != null) {
            db.messageDao().updatePayloadText(p.messageId.toString(), sos.message)
            _state.update { it.copy(lastSos = sos) }
            // Only a structurally valid SOS packet may activate the local siren.
            emergency.triggerLocal()
            log("SOS", "Emergency surfaced locally", p.senderNodeId.toString(), p.messageId.toString())
        } else {
            log("SOS", "Rejected malformed SOS payload", p.senderNodeId.toString(), p.messageId.toString())
        }
    }

    private suspend fun handleIncomingSosReview(p: MeshPacket) {
        if (p.payload.size < 17) return
        val b = ByteBuffer.wrap(p.payload)
        val originalId = UUID(b.long, b.long).toString()
        val passed = b.get().toInt() == 1
        val status = if (passed) "PASS" else "REJECT"
        db.messageDao().updateStatus(originalId, "SOS_${status}", "Remote SOS review: $status")
        _state.update { state ->
            val sos = state.lastSos
            if (sos != null && sos.messageId == originalId) state.copy(lastSos = sos.copy(reviewStatus = status)) else state
        }
        log("SOS", "SOS review received: $status", p.senderNodeId.toString(), originalId)
    }

    private suspend fun handleIncomingLocation(p: MeshPacket) {
        if (p.payload.size < 28) return
        val b = ByteBuffer.wrap(p.payload)
        val lat = b.double
        val lon = b.double
        val accuracy = b.float
        val timestamp = b.long
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        val display = PeerLocationDisplay(p.senderNodeId.toString(), resolveName(p.senderNodeId), lat, lon, accuracy, timestamp)
        _state.update { state ->
            val refreshed = state.peerLocations + (p.senderNodeId.toString() to display)
            state.copy(peerLocations = refreshed)
        }
        log("LOCATION", "Peer location received: %.5f, %.5f ±%.0fm".format(lat, lon, accuracy), p.senderNodeId.toString(), p.messageId.toString())
    }

    private suspend fun handleIncomingAlert(p: MeshPacket) {
        val text = runCatching { String(p.payload, Charsets.UTF_8).trim() }.getOrDefault("")
        if (text.isBlank()) return
        val sender = resolveName(p.senderNodeId)
        _state.update { it.copy(lastAlert = AlertDisplay(sender, text, p.createdAt, false)) }
        // Receiver-side attention signal. The sender's local UI must not be the only
        // device that produces feedback for an alert.
        emergency.triggerAlertLocal()
        log("ALERT", "Alert received: $text", p.senderNodeId.toString(), p.messageId.toString())
    }

    private suspend fun handleIncomingVoice(p: MeshPacket) {
        VoiceTransfer.decode(p.payload)?.let { chunk ->
            // One logical UI message per voice transfer. Transport chunks have their
            // own packet IDs, but all chunks belonging to one recording share the
            // voice message ID. The old code created many chunk rows and therefore
            // the chat could only expose one playable recording.
            val logicalId = chunk.messageId.toString()
            val existing = db.messageDao().byId(logicalId)
            if (existing == null) {
                db.messageDao().insertMessage(
                    MessageEntity(
                        logicalId, p.originNodeId.toString(), p.originNodeId.toString(),
                        p.destinationNodeId?.toString() ?: "*", resolveName(p.originNodeId), PacketType.VOICE_CHUNK.name, 10,
                        "RECEIVED", p.createdAt, p.createdAt + 24 * 60 * 60_000L, p.ttl, p.hopCount,
                        null, "Voice message", false, true
                    )
                )
            }
            voiceReassembler.accept(chunk)?.let { file ->
                db.voiceAssetDao().insert(
                    VoiceAssetEntity(logicalId, "AAC", chunk.durationMs, file.absolutePath, chunk.total, chunk.total)
                )
                db.messageDao().attachPayloadFile(logicalId, file.absolutePath)
                _state.update { state ->
                    val updatedSos = state.lastSos?.takeIf { it.messageId == logicalId }
                        ?.copy(hasVoice = true, voicePath = file.absolutePath)
                    state.copy(lastVoicePath = file.absolutePath, lastSos = updatedSos ?: state.lastSos)
                }
                log("VOICE", "Reassembled voice message ${chunk.messageId}", p.senderNodeId.toString(), logicalId)
            }
        }
    }

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    private fun resolveName(node:UUID): String {
        val id = node.toString()
        return peers.values.firstOrNull { it.nodeId.equals(id, true) }?.displayName?.takeIf { it.isNotBlank() && it != "Authenticating…" }
            ?: dbPeerNameCache[id]
            ?: "Node-${id.takeLast(4)}"
    }

    // Updated from HELLO handling so UI/message rendering has a stable name even
    // while BLE scan callbacks continue arriving with their temporary placeholder.
    private val dbPeerNameCache = ConcurrentHashMap<String, String>()

    private suspend fun handleHello(address:String,device:BluetoothDevice?,p:MeshPacket){
        val h=HelloCodec.decode(p.payload)?:return
        if(h.nodeId!=p.senderNodeId)return

        // If this BLE address was recently observed in an advertisement, bind the
        // authenticated HELLO identity to the UUID advertised by that same address.
        // Keep the one-way-discovery path: an incoming GATT peer may have no scan row.
        val advertisedPeer = peers[address]
        val advertisedNodeId = advertisedPeer
            ?.takeIf { System.currentTimeMillis() - it.lastSeen <= 30_000L }
            ?.nodeId
            ?.toUuidOrNull()
        if (advertisedNodeId != null && advertisedNodeId != h.nodeId) {
            log("DROP", "HELLO identity does not match recent BLE advertisement", h.nodeId.toString(), p.messageId.toString())
            return
        }

        val valid=try{Signature.getInstance("SHA256withECDSA").apply{initVerify(java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(h.publicKey)));update(h.nodeId.toString().toByteArray());update(h.publicKey)}.verify(h.signature)}catch(_:Throwable){false}
        if(!valid){log("DROP","Invalid HELLO signature",h.nodeId.toString(),p.messageId.toString());return}
        val wasAuthenticated = crypto.hasSession(h.nodeId)
        if(!crypto.registerPeer(h.nodeId,h.publicKey)){return}
        deviceToNode[address]=h.nodeId;device?.let{nodeToDevice[h.nodeId]=it}
        dbPeerNameCache[h.nodeId.toString()] = h.displayName
        val authenticatedAt = System.currentTimeMillis()
        val observedPeer = peers[address]
        peers[address] = if (observedPeer != null) {
            observedPeer.copy(nodeId = h.nodeId.toString(), displayName = h.displayName, lastSeen = authenticatedAt)
        } else {
            MeshPeer(address = address, nodeId = h.nodeId.toString(), rssi = -100,
                displayName = h.displayName, lastSeen = authenticatedAt)
        }
        _state.update { state ->
            val updatedLocations = state.peerLocations.mapValues { (id, loc) ->
                if (id.equals(h.nodeId.toString(), true)) loc.copy(name = h.displayName) else loc
            }
            state.copy(peerLocations = updatedLocations)
        }
        scope.launch {
            db.peerDao().insertPeer(
                PeerEntity(h.nodeId.toString(), address, h.displayName, System.currentTimeMillis(),
                    "CONNECTED", peers[address]?.rssi ?: -100, "TEXT,LOCATION,SOS,VOICE", "UNKNOWN")
            )
        }
        publishAuthenticatedPeers()
        log("AUTH","Authenticated peer ${h.nodeId}",h.nodeId.toString(),null)

        // If this HELLO arrived through our GATT server, the remote phone may
        // not have discovered us independently. Reply with our own HELLO so
        // both directions become authenticated even with one-way discovery.
        if (!wasAuthenticated && device != null) {
            val ourHello = security.encode(sendHelloPacket())
            server?.sendToDevice(device, ourHello)
        }

        if (!wasAuthenticated) {
            val capabilities = security.encodeForPeer(
                basePacket(UUID.randomUUID(), null, PacketType.CAPABILITIES, 90, "TEXT,LOCATION,SOS,VOICE".toByteArray(), 1, 60_000),
                h.nodeId
            )
            sendToAddress(address, h.nodeId, capabilities, device)
        }
        flushQueue()
    }

    private suspend fun route(p: MeshPacket, incomingAddress: String? = null): Boolean {
        val authenticated = peers.entries.mapNotNull { (addr, peer) ->
            val peerId = deviceToNode[addr] ?: return@mapNotNull null
            if (!crypto.hasSession(peerId) || addr == incomingAddress) return@mapNotNull null
            Triple(addr, peer, peerId)
        }
        val directDestination = p.destinationNodeId?.let { dest -> authenticated.firstOrNull { it.third == dest } }
        val candidates = if (directDestination != null) listOf(directDestination) else authenticated
        var sent = false
        for ((addr, peer, peerId) in candidates) {
            val encoded = security.encodeForPeer(p, peerId)
            if (sendToAddress(addr, peerId, encoded, nodeToDevice[peerId])) {
                sent = true
                log(if (incomingAddress == null) "SEND" else "RELAY", "${p.type} via ${peer.displayName} hop=${p.hopCount}", peerId.toString(), p.messageId.toString())
            }
        }
        db.messageDao().updateStatus(
            p.messageId.toString(),
            if (sent) "FORWARDED" else "QUEUED",
            if (sent) null else "No eligible authenticated peer"
        )
        return sent
    }

    private suspend fun forward(p: MeshPacket, incomingAddress: String, text: String?) {
        if (p.ttl <= 0) {
            db.messageDao().updateStatus(p.messageId.toString(), "EXPIRED", "TTL exhausted")
            return
        }
        val next = p.copy(
            senderNodeId = identity.nodeId,
            ttl = max(0, p.ttl - 1),
            hopCount = p.hopCount + 1
        )
        persistRelay(next, text)
        route(next, incomingAddress)
    }

    private fun sendAckIfPossible(original: MeshPacket, address: String, device: BluetoothDevice?) {
        scope.launch {
            val ackPayload = ByteBuffer.allocate(16)
                .putLong(original.messageId.mostSignificantBits)
                .putLong(original.messageId.leastSignificantBits)
                .array()
            // ACK is addressed to the original origin. Each relay forwards it
            // toward that origin, so A can receive the ACK from a 3+ hop route.
            val ack = basePacket(
                UUID.randomUUID(), original.originNodeId, PacketType.ACK, 100, ackPayload,
                ttl = 6, expiry = 5 * 60_000L
            )
            val peerId = deviceToNode[address] ?: return@launch
            if (!crypto.hasSession(peerId)) return@launch
            val encoded = security.encodeForPeer(ack, peerId)
            sendToAddress(address, peerId, encoded, device)
        }
    }

    /** Requeue a persisted text/SOS/alert packet using its original message ID. */
    suspend fun retryQueuedMessage(messageId: String): Boolean {
        val message = db.messageDao().byId(messageId) ?: return false
        val retryableTypes = setOf(
            PacketType.TEXT.name, PacketType.SOS.name, PacketType.ALERT.name,
            PacketType.LOCATION.name, PacketType.SOS_REVIEW.name
        )
        if (message.type !in retryableTypes) return false
        if (message.status !in setOf("QUEUED", "FORWARDED")) return false
        if (message.expiresAt <= System.currentTimeMillis() || message.ttl <= 0) return false
        if (message.payloadReference.isNullOrBlank()) return false
        db.messageDao().updateStatus(messageId, "QUEUED", "Manual retry requested")
        flushQueue()
        return true
    }

    private suspend fun flushQueue(){
        val now = System.currentTimeMillis()
        val list = db.messageDao().queued(now, 20)
        for (m in list) {
            if (now > m.expiresAt || m.ttl <= 0) {
                db.messageDao().updateStatus(m.messageId, "EXPIRED", "Queue item expired")
                continue
            }
            val payload = Base64.decode(m.payloadReference ?: "", Base64.NO_WRAP)
            val p = MeshPacket(
                1, UUID.fromString(m.messageId), UUID.fromString(m.senderNodeId), UUID.fromString(m.originNodeId),
                m.receiverNodeId.takeIf { it != "*" }?.let(UUID::fromString), PacketType.valueOf(m.type), m.priority,
                m.createdAt, m.ttl, m.hopCount, 0, payload
            )
            route(p)
        }
    }
    private suspend fun maintenance(){
        while(currentCoroutineContext().isActive){
            db.messageDao().deleteExpired(System.currentTimeMillis())
            db.peerDao().deleteStale(System.currentTimeMillis()-30_000)
            // Re-check local Bluetooth state and permissions. This lets the mesh
            // recover when the user toggles Bluetooth back on without restarting the app.
            runCatching { server?.start() }
            runCatching { ble?.start() }
            flushQueue()
            retryEligibleConnections()
            val queueSize = runCatching { db.messageDao().queueSize() }.getOrDefault(0)
            val now = System.currentTimeMillis()
            // BLE private addresses can rotate, so keep the internal address map for transport
            // but expose one authenticated UI row per stable cryptographic node identity.
            peers.entries.removeIf { entry ->
                now - entry.value.lastSeen > 60_000L &&
                    !clients.containsKey(entry.key) && server?.hasConnection(entry.key) != true
            }
            peerFirstSeenAt.entries.removeIf { now - (peers[it.key]?.lastSeen ?: 0L) > 60_000L }
            val discoveredPeers = peers.values
                .filter { now - it.lastSeen <= 20_000L }
                .groupBy { it.nodeId.lowercase(Locale.ROOT) }
                .values.mapNotNull { group -> group.maxByOrNull { it.lastSeen } }
            val activePeers = peers.values.filter { peer ->
                val node = deviceToNode[peer.address]
                val linkUp = clients.containsKey(peer.address) || server?.hasConnection(peer.address) == true
                node != null && crypto.hasSession(node) && linkUp
            }.groupBy { it.nodeId.lowercase(Locale.ROOT) }
                .values.mapNotNull { group -> group.maxByOrNull { it.lastSeen } }
            _state.update { it.copy(discoveredPeers = discoveredPeers, peers = activePeers, queued = queueSize) }
            replay.trim()
            delay(5_000)
        }
    }
    private fun publishAuthenticatedPeers() {
        // A peer may be observed under more than one BLE address (for example after
        // address rotation). UI identity is the authenticated node ID, not the address.
        val authenticated = peers.values.filter { peer ->
            val node = deviceToNode[peer.address]
            node != null && crypto.hasSession(node)
        }.groupBy { it.nodeId.lowercase(Locale.ROOT) }
            .values.mapNotNull { group -> group.maxByOrNull { it.lastSeen } }
        val now = System.currentTimeMillis()
        val discovered = peers.values
            .filter { now - it.lastSeen <= 20_000L }
            .groupBy { it.nodeId.lowercase(Locale.ROOT) }
            .values.mapNotNull { group -> group.maxByOrNull { it.lastSeen } }
        _state.update { it.copy(discoveredPeers = discovered, peers = authenticated) }
    }

    private fun setStatus(s:String){_state.update{it.copy(status=s)}}
    private fun setBleStatus(s: String) { _state.update { it.copy(status = s, bleStatus = s) } }

    private companion object {
        // Android devices commonly support only a handful of simultaneous GATT links.
        // This is an outgoing-central budget; the mesh forwards through connected peers.
        const val MAX_OUTGOING_GATT_LINKS = 7
        const val FALLBACK_INITIATOR_GRACE_MS = 15_000L
    }
    private fun log(cat:String,details:String,node:String?,msg:String?){
        scope.launch{db.eventDao().insert(EventEntity(UUID.randomUUID().toString(),System.currentTimeMillis(),cat,node,msg,details))}
    }
}
