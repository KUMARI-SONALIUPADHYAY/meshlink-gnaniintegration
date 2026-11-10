@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.meshlink.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.content.Intent
import android.content.Context
import android.media.MediaMetadataRetriever
import android.speech.tts.TextToSpeech
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.Window
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.meshlink.app.core.bluetooth.MeshPeer
import com.meshlink.app.core.ai.GnaniVoiceApi
import com.meshlink.app.core.ai.PrismaProgress
import com.meshlink.app.core.ai.EvonApiClient
import com.meshlink.app.core.ai.CloudTranslationClient
import com.meshlink.app.core.mesh.CloudSyncCoordinator
import com.meshlink.app.core.cloud.CloudApiClient
import com.meshlink.app.core.cloud.CloudDashboardState
import com.meshlink.app.core.cloud.CloudIncident
import com.meshlink.app.core.cloud.CloudSyncScheduler
import com.meshlink.app.core.location.LocationProvider
import com.meshlink.app.ui.cloud.CloudDashboard
import com.meshlink.app.core.mesh.MeshEngine
import com.meshlink.app.core.mesh.MeshUiState
import com.meshlink.app.core.safety.SafetyDecision
import com.meshlink.app.core.voice.VoicePlayer
import com.meshlink.app.core.voice.VoicePlaybackState
import com.meshlink.app.core.ai.TimbreCatalog
import com.meshlink.app.data.database.EventEntity
import com.meshlink.app.data.database.MessageEntity
import com.meshlink.app.service.MeshForegroundService
import com.meshlink.app.ui.theme.MeshLinkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var engine: MeshEngine
    @Inject lateinit var gnaniVoiceApi: GnaniVoiceApi
    @Inject lateinit var evonApiClient: EvonApiClient
    @Inject lateinit var translationClient: CloudTranslationClient
    @Inject lateinit var cloudSyncCoordinator: CloudSyncCoordinator
    private val latestVoiceFile = mutableStateOf<File?>(null)
    private val latestVoiceMessageId = mutableStateOf("")
    private val aiBusy = mutableStateOf(false)
    private val aiRecording = mutableStateOf(false)
    private var pendingAiRecording = false
    private lateinit var audioPicker: ActivityResultLauncher<Array<String>>
    private lateinit var cloudImagePicker: ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest>
    private lateinit var cloudApi: CloudApiClient
    private val cloudDashboardState = mutableStateOf(CloudDashboardState())
    private val selectedCloudImageUri = mutableStateOf<Uri?>(null)
    private val selectedCloudImageName = mutableStateOf("")
    private val aiStatus = mutableStateOf("")
    private val aiTranscript = mutableStateOf("")
    private val evonAnswer = mutableStateOf("")
    private val emergencyAnalysis = mutableStateOf("")
    private val translatedTranscript = mutableStateOf("")
    private val translatedResponse = mutableStateOf("")
    private val aiAudioPath = mutableStateOf<String?>(null)
    private val aiAudioMessageId = mutableStateOf("")
    private val aiPlaybackState = mutableStateOf(VoicePlaybackState.IDLE)
    private val voicePlayer = VoicePlayer()
    private val playingVoicePath = mutableStateOf<String?>(null)
    private val internetAvailable = mutableStateOf(false)
    private var pendingLocation = false
    private var lastVoiceFile: File? = null
    private var retryingAiQueue = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runOnUiThread { refreshInternetStatus() }
        override fun onLost(network: Network) = runOnUiThread { refreshInternetStatus() }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            runOnUiThread {
                internetAvailable.value = validated
                if (validated) {
                    retryPendingAiWorkflows()
                    runCatching { CloudSyncScheduler.enqueue(this@MainActivity) }
                    syncCloudOutbox()
                    refreshCloudDashboard(false)
                }
            }
        }
    }

    private fun refreshInternetStatus() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        internetAvailable.value = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
    }

    private val bluetoothEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (result.resultCode == RESULT_OK && adapter?.isEnabled == true) {
            MeshForegroundService.start(this)
        } else {
            aiStatus.value = "Bluetooth is still off. Turn it on in Android settings to discover nearby MeshLink phones."
        }
    }

    private fun startMeshAfterBluetoothCheck() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (adapter?.isEnabled == true) MeshForegroundService.start(this)
        else bluetoothEnableLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private val blePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val required = if (android.os.Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (required.all { result[it] == true || ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startMeshAfterBluetoothCheck()
        }
    }

    private val locationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.all { it } ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@registerForActivityResult
        if (pendingLocation) { pendingLocation = false; lifecycleScope.launch { engine.sendLocation() } }
    }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            engine.startVoiceRecording()
            if (pendingAiRecording) aiRecording.value = true
        }
        pendingAiRecording = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.WHITE
        window.navigationBarColor = AndroidColor.WHITE
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        voicePlayer.onPlaybackChanged = { path -> runOnUiThread { playingVoicePath.value = path } }
        voicePlayer.onPlaybackStateChanged = { state -> runOnUiThread { aiPlaybackState.value = state } }
        audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importVoiceRecording(uri)
        }
        cloudApi = CloudApiClient(this)
        cloudImagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            selectedCloudImageUri.value = uri
            selectedCloudImageName.value = uri?.lastPathSegment?.substringAfterLast('/')?.take(80).orEmpty()
        }
        refreshInternetStatus()
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        }
        setContent {
            MeshLinkTheme {
                val internetIsAvailable by internetAvailable
                val modePreferences = getSharedPreferences("meshlink_settings", MODE_PRIVATE)
                if (modePreferences.getString("app_mode", null) == null) {
                    getPreferences(0).getString("app_mode", null)?.let { legacy -> modePreferences.edit().putString("app_mode", legacy).apply() }
                }
                var appMode by remember {
                    mutableStateOf(AppMode.from(modePreferences.getString("app_mode", null)))
                }
                val state by engine.state.collectAsState()
                val messages by engine.messages.collectAsState(initial = emptyList())
                val events by engine.events.collectAsState(initial = emptyList())
                val safety by engine.safetyMessages.collectAsState(initial = emptyList())
                val latestVoice by latestVoiceFile
                val latestVoiceId by latestVoiceMessageId
                val aiBusyValue by aiBusy
                val aiRecordingValue by aiRecording
                val aiStatusValue by aiStatus
                val aiTranscriptValue by aiTranscript
                val evonAnswerValue by evonAnswer
                val emergencyAnalysisValue by emergencyAnalysis
                val translatedTranscriptValue by translatedTranscript
                val translatedResponseValue by translatedResponse
                val aiAudioPathValue by aiAudioPath
                val aiAudioMessageIdValue by aiAudioMessageId
                val aiPlaybackStateValue by aiPlaybackState
                val cloudStateValue by cloudDashboardState
                val cloudImageUriValue by selectedCloudImageUri
                val cloudImageNameValue by selectedCloudImageName
                var onboarding by remember { mutableStateOf(!getPreferences(0).getBoolean("onboarded", false)) }

                if (appMode == null) {
                    ModeSelectionScreen(
                        internetAvailable = internetIsAvailable,
                        onSelect = { selectedMode ->
                            modePreferences.edit().putString("app_mode", selectedMode.storageValue).apply()
                            appMode = selectedMode
                            if (selectedMode == AppMode.ONLINE) { runCatching { CloudSyncScheduler.enqueue(this@MainActivity) }; syncCloudOutbox(); refreshCloudDashboard(false) }
                        }
                    )
                } else MeshLinkScreen(
                    state = state,
                    appMode = appMode!!,
                    internetAvailable = internetIsAvailable,
                    onChangeMode = {
                        modePreferences.edit().remove("app_mode").apply()
                        appMode = null
                    },
                    messages = messages,
                    events = events,
                    safety = safety,
                    latestVoiceFile = latestVoice,
                    latestVoiceMessageId = latestVoiceId,
                    onSelectVoiceFile = { audioPicker.launch(arrayOf("audio/*")) },
                    aiRecording = aiRecordingValue,
                    onStartAiRecording = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            engine.startVoiceRecording(); aiRecording.value = true
                        } else { pendingAiRecording = true; audioPermission.launch(Manifest.permission.RECORD_AUDIO) }
                    },
                    onStopAiRecording = {
                        val file = engine.stopVoiceRecording()
                        aiRecording.value = false
                        if (file != null) {
                            val id = UUID.randomUUID().toString()
                            latestVoiceFile.value = file
                            latestVoiceMessageId.value = id
                            aiTranscript.value = ""
                            evonAnswer.value = ""
                            emergencyAnalysis.value = ""
                            translatedTranscript.value = ""
                            translatedResponse.value = ""
                            aiAudioPath.value = null
                            aiAudioMessageId.value = ""
                            lastVoiceFile = file
                            lifecycleScope.launch { engine.registerLocalVoiceAsset(id, file, 0L) }
                            aiStatus.value = "Emergency voice recording saved locally. Ready for Prisma."
                        } else aiStatus.value = "No voice recording was saved."
                    },
                    gnaniConfigured = gnaniVoiceApi.isConfigured(),
                    evonConfigured = evonApiClient.isConfigured(),
                    translationConfigured = translationClient.isConfigured(),
                    evonAnswer = evonAnswerValue,
                    emergencyAnalysis = emergencyAnalysisValue,
                    translatedTranscript = translatedTranscriptValue,
                    translatedResponse = translatedResponseValue,
                    onRetryQueued = { retryPendingAiWorkflows() },
                    onShareEmergencySummary = { summary ->
                        lifecycleScope.launch {
                            engine.broadcastText("MESHLink AI-GENERATED EMERGENCY SUMMARY — REVIEWED BY SENDER\n$summary\nThis is an advisory report, not a confirmed dispatch.")
                            aiStatus.value = "Reviewed emergency summary sent to nearby mesh peers. SOS was not triggered."
                        }
                    },
                    onEvonAnalyze = { text, messageId ->
                        lifecycleScope.launch {
                            aiBusy.value = true
                            aiStatus.value = "Evon is extracting emergency information…"
                            try {
                                val analysis = evonApiClient.analyzeEmergency(text, messageId)
                                engine.saveEmergencyAnalysis(messageId, analysis)
                                evonAnswer.value = analysis.suggestedResponse
                                emergencyAnalysis.value = buildString {
                                    append("Emergency: ${analysis.emergencyType}\nSeverity: ${analysis.severity}\n")
                                    append("Location mentioned: ${analysis.locationMentioned ?: "Not stated"}\n")
                                    append("People affected: ${analysis.peopleAffected?.toString() ?: "Not stated"}\n")
                                    append("Immediate assistance: ${analysis.immediateAssistance.ifBlank { "Not specified" }}")
                                    if (analysis.instructions.isNotEmpty()) append("\nInstructions: ${analysis.instructions.joinToString("; ")}")
                                    if (analysis.warnings.isNotEmpty()) append("\nWarnings: ${analysis.warnings.joinToString("; ")}")
                                }
                                engine.clearAiWorkflow(messageId, "EVON")
                                aiStatus.value = "Evon emergency analysis saved. Review all AI-generated suggestions."
                            } catch (error: Exception) {
                                aiStatus.value = error.message ?: "Evon analysis failed."
                            } finally { aiBusy.value = false }
                        }
                    },
                    onTranslateEmergency = { text, source, target, messageId, kind ->
                        lifecycleScope.launch {
                            aiBusy.value = true
                            aiStatus.value = "Translating emergency information…"
                            try {
                                val result = translationClient.translate(text, target, source)
                                engine.saveEmergencyTranslation(messageId, result, kind)
                                engine.clearAiWorkflow(messageId, if (kind == "report") "TRANSLATE_REPORT" else "TRANSLATE_RESPONSE")
                                if (kind == "report") translatedTranscript.value = result.translatedText
                                else translatedResponse.value = result.translatedText
                                aiStatus.value = "Translation saved (${result.sourceLanguage} → ${result.targetLanguage})."
                            } catch (error: Exception) {
                                val audio = latestVoiceFile.value
                                if (error is com.meshlink.app.core.ai.GnaniApiException && error.error == com.meshlink.app.core.ai.GnaniApiError.NETWORK && audio?.isFile == true) {
                                    val stage = if (kind == "report") "TRANSLATE_REPORT" else "TRANSLATE_RESPONSE"
                                    runCatching { engine.queueAiWorkflow(messageId, audio, source, target, "", stage, error.message, text) }
                                    aiStatus.value = "Network interrupted. Translation is queued; original text is preserved."
                                } else aiStatus.value = error.message ?: "Translation failed. Original text is preserved."
                            } finally { aiBusy.value = false }
                        }
                    },
                    onEvonAsk = { prompt ->
                        lifecycleScope.launch {
                            aiBusy.value = true
                            aiStatus.value = "Sending your question to the configured Evon inference server…"
                            try {
                                evonAnswer.value = evonApiClient.ask(prompt)
                                aiStatus.value = "Evon response received. Review it before acting on emergency information."
                            } catch (error: Exception) {
                                aiStatus.value = error.message ?: "Evon request failed."
                            } finally { aiBusy.value = false }
                        }
                    },
                    aiBusy = aiBusyValue,
                    aiStatus = aiStatusValue,
                    aiTranscript = aiTranscriptValue,
                    aiAudioPath = aiAudioPathValue,
                    aiAudioMessageId = aiAudioMessageIdValue,
                    aiPlaybackState = aiPlaybackStateValue,
                    onPrismaTranscribe = { file, messageId, language ->
                        lifecycleScope.launch {
                            aiBusy.value = true
                            aiStatus.value = "Uploading recording to Prisma…"
                            try {
                                val result = gnaniVoiceApi.transcribe(
                                    file, language, messageId
                                ) { progress ->
                                    runOnUiThread {
                                        aiStatus.value = when (progress) {
                                            PrismaProgress.UPLOADING -> "Uploading recording to Prisma…"
                                            PrismaProgress.PROCESSING -> "Prisma is processing the emergency voice message…"
                                        }
                                    }
                                }
                                engine.saveVoiceTranscription(messageId, result.text, language)
                                engine.clearAiWorkflow(messageId, "PRISMA")
                                aiTranscript.value = result.text
                                translatedTranscript.value = ""
                                translatedResponse.value = ""
                                emergencyAnalysis.value = ""
                                aiStatus.value = if (result.truncated) "Transcription saved, but Prisma marked it as truncated. Consider a shorter clip." else if (result.text.isBlank()) "Prisma completed: no speech was detected. Original audio is preserved." else if (evonApiClient.isConfigured()) "Prisma transcription saved. Evon is analyzing the emergency…" else "Prisma transcription saved. Configure the Evon inference endpoint to extract emergency information."
                                if (result.text.isNotBlank() && evonApiClient.isConfigured()) {
                                    try {
                                        val analysis = evonApiClient.analyzeEmergency(result.text, messageId)
                                        engine.saveEmergencyAnalysis(messageId, analysis)
                                        evonAnswer.value = analysis.suggestedResponse
                                        emergencyAnalysis.value = buildString {
                                            append("Emergency: ${analysis.emergencyType}\nSeverity: ${analysis.severity}\n")
                                            append("Location mentioned: ${analysis.locationMentioned ?: "Not stated"}\n")
                                            append("People affected: ${analysis.peopleAffected?.toString() ?: "Not stated"}\n")
                                            append("Immediate assistance: ${analysis.immediateAssistance.ifBlank { "Not specified" }}")
                                            if (analysis.instructions.isNotEmpty()) append("\nInstructions: ${analysis.instructions.joinToString("; ")}")
                                            if (analysis.warnings.isNotEmpty()) append("\nWarnings: ${analysis.warnings.joinToString("; ")}")
                                        }
                                        aiStatus.value = "Prisma transcription and Evon emergency analysis saved. Review AI suggestions."
                                    } catch (analysisError: Exception) {
                                        if (analysisError is com.meshlink.app.core.ai.GnaniApiException && analysisError.error == com.meshlink.app.core.ai.GnaniApiError.NETWORK && file.isFile) {
                                            runCatching { engine.queueAiWorkflow(messageId, file, language, "", "", "EVON", analysisError.message, result.text) }
                                            aiStatus.value = "Prisma transcription saved. Evon is offline; the analysis task is queued for retry."
                                        } else aiStatus.value = "Prisma transcription saved. Evon analysis unavailable: ${analysisError.message ?: "request failed"}"
                                    }
                                }
                            } catch (error: Exception) {
                                if (error is com.meshlink.app.core.ai.GnaniApiException && error.error == com.meshlink.app.core.ai.GnaniApiError.NETWORK && file.isFile) {
                                    runCatching { engine.queueAiWorkflow(messageId, file, language, "", "", "PRISMA", error.message) }
                                    aiStatus.value = "Internet interrupted. Original audio is preserved and this Prisma task is queued for retry."
                                } else aiStatus.value = error.message ?: "Prisma transcription failed. Original audio is preserved."
                            } finally { aiBusy.value = false }
                        }
                    },
                    onTimbreSynthesize = { text, language, voice, messageId ->
                        lifecycleScope.launch {
                            aiBusy.value = true
                            aiStatus.value = "Sending text to Timbre and generating speech…"
                            try {
                                val result = gnaniVoiceApi.synthesize(text, language, voice, messageId)
                                engine.saveGeneratedSpeech(result.messageId, result.file, result.language, result.voice, result.model)
                                engine.clearAiWorkflow(messageId, "TIMBRE")
                                aiAudioPath.value = result.file.absolutePath
                                aiAudioMessageId.value = result.messageId
                                aiStatus.value = "Timbre audio saved and linked to emergency message ${result.messageId}."
                            } catch (error: Exception) {
                                val audio = latestVoiceFile.value
                                if (error is com.meshlink.app.core.ai.GnaniApiException && error.error == com.meshlink.app.core.ai.GnaniApiError.NETWORK && audio?.isFile == true) {
                                    runCatching { engine.queueAiWorkflow(messageId, audio, "", language, voice, "TIMBRE", error.message, text) }
                                    aiStatus.value = "Network interrupted. Timbre speech is queued; source text and original audio are preserved."
                                } else aiStatus.value = error.message ?: "Timbre synthesis failed."
                            } finally { aiBusy.value = false }
                        }
                    },
                    onPlayAiAudio = { path -> voicePlayer.play(this, path) },
                    onPauseAiAudio = { voicePlayer.pause() },
                    onResumeAiAudio = { voicePlayer.resume() },
                    onReplayAiAudio = { path -> voicePlayer.replay(this, path) },
                    onMeshToggle = ::toggleMesh,
                    onText = { peer, text -> peer.nodeId.toUuidOrNull()?.let { lifecycleScope.launch { engine.sendText(it, text) } } },
                    onBroadcast = { lifecycleScope.launch { engine.broadcastText(it) } },
                    onSubmitCoordinationRecord = { recordType, payloadJson, cloudConsent ->
                        lifecycleScope.launch {
                            val recordId = UUID.randomUUID().toString()
                            val summary = "SAHAYAKNET $recordType REPORT\n$payloadJson\nUnverified report; human review required.".take(2600)
                            runCatching { engine.broadcastText(summary) }
                            if (cloudConsent) {
                                runCatching {
                                    cloudSyncCoordinator.stageCoordinationRecord(true, recordId, recordType, payloadJson, cloudApi.accountId())
                                    if (internetAvailable.value) syncCloudOutbox() else runCatching { CloudSyncScheduler.enqueue(this@MainActivity) }
                                }.onFailure { aiStatus.value = "Saved to mesh/local queue; cloud sync pending: ${it.message}" }
                            }
                            aiStatus.value = if (cloudConsent) "Report submitted to mesh transport and staged for consented SahayakNet sync. Mesh delivery requires acknowledgement." else "Report submitted to the local mesh transport. Delivery depends on connected peers and acknowledgement; cloud sharing was not enabled."
                        }
                    },
                    onRetryMessage = { messageId -> lifecycleScope.launch { engine.retryQueuedMessage(messageId) } },
                    onAlert = { lifecycleScope.launch { engine.sendAlert(null, it) } },
                    onLocation = { pendingLocation = true; requestLocationPermission() },
                    onSos = { message, voice, voiceMessageId, shareLocation, destinationNodeId ->
                        // SOS transmission never waits for GPS. Coordinates are read only
                        // when the user explicitly enabled location sharing in the SOS form.
                        lifecycleScope.launch {
                            // Transmit SOS over the local mesh immediately; cloud sync is an independent follow-on.
                            val sentSosId = engine.sendSos(destination = destinationNodeId?.toUuidOrNull(), voiceFile = voice, message = message, voiceMessageId = voiceMessageId?.toUuidOrNull(), shareLocation = shareLocation)
                            if (getPreferences(0).getBoolean("cloud_sync_sos_consent", false)) {
                                // Cloud staging is best-effort and cannot change the already transmitted local SOS.
                                runCatching {
                                    cloudSyncCoordinator.stageConsentGrantedEmergencySummary(true, sentSosId.toString(), "SOS", message.ifBlank { "SOS alert sent over local mesh" }, cloudApi.accountId())
                                    runCatching { CloudSyncScheduler.enqueue(this@MainActivity) }
                                    syncCloudOutbox()
                                }
                            }
                        }
                    },
                    onVoiceStart = {
                        aiStatus.value = "Recording emergency voice message…"
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) engine.startVoiceRecording()
                        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSosVoiceStart = {
                        aiStatus.value = "Recording SOS voice message…"
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) engine.startVoiceRecording()
                        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSosVoiceStop = {
                        lastVoiceFile = engine.stopVoiceRecording()
                        latestVoiceFile.value = lastVoiceFile
                        lastVoiceFile?.let { file ->
                            val id = UUID.randomUUID().toString()
                            latestVoiceMessageId.value = id
                            lifecycleScope.launch { engine.registerLocalVoiceAsset(id, file, 0L) }
                            aiStatus.value = "SOS voice recording saved. Ready for Prisma transcription."
                        }
                        lastVoiceFile
                    },
                    onVoiceStop = { peer ->
                        lastVoiceFile = engine.stopVoiceRecording()
                        latestVoiceFile.value = lastVoiceFile
                        lastVoiceFile?.let { file ->
                            val id = UUID.randomUUID()
                            latestVoiceMessageId.value = id.toString()
                            aiStatus.value = "Voice recording saved. Ready for Prisma transcription."
                            lifecycleScope.launch {
                                engine.registerLocalVoiceAsset(id.toString(), file)
                                engine.sendVoice(id, file, peer?.nodeId?.toUuidOrNull())
                            }
                        }
                    },
                    onPlayVoice = { state.lastVoicePath?.let { path ->
                        if (playingVoicePath.value == path) voicePlayer.stop() else voicePlayer.play(this, path)
                    } },
                    onPlayVoicePath = { path ->
                        if (playingVoicePath.value == path) voicePlayer.stop() else voicePlayer.play(this, path)
                    },
                    onNameChange = engine::setDisplayName,
                    onAvatarChange = engine::setAvatarIndex,
                    onSafetyReview = { id, decision -> lifecycleScope.launch { engine.reviewSafety(id, decision) } },
                    onSosReview = { id, decision -> lifecycleScope.launch { engine.reviewSos(id, decision) } },
                    cloudState = cloudStateValue,
                    cloudConfigured = cloudApi.isConfigured(),
                    selectedCloudImageUri = cloudImageUriValue,
                    selectedCloudImageName = cloudImageNameValue,
                    onCloudAuthenticate = ::authenticateCloud,
                    onCloudLogout = { cloudApi.logout(); cloudDashboardState.value = CloudDashboardState(shareSos = getPreferences(0).getBoolean("cloud_sync_sos_consent", false), message = "Signed out.") },
                    onCloudRefresh = ::refreshCloudDashboard,
                    onCloudSubmit = ::submitCommunityReport,
                    onCloudPickImage = { cloudImagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onCloudStatus = ::updateCloudIncidentStatus,
                    onCloudVerification = ::updateCloudIncidentVerification,
                    onCloudDispute = ::disputeCloudIncident,
                    onCloudSync = ::syncCloudOutbox,
                    onCloudSosConsent = { enabled -> getPreferences(0).edit().putBoolean("cloud_sync_sos_consent", enabled).apply(); cloudDashboardState.value = cloudDashboardState.value.copy(shareSos = enabled, message = if (enabled) "Future SOS summaries will sync after local transmission." else "Future SOS cloud sharing disabled.", error = false) },
                    onCloudOpenMap = ::openIncidentMap,
                    onCloudViewMedia = ::viewCloudMedia,
                    playingVoicePath = playingVoicePath.value
                )

                if (onboarding && appMode != null) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Welcome to MeshLink") },
                        text = { Text("Private, nearby communication over BLE. Peers are discovered automatically after permissions. Wi-Fi and mobile data are not required for the mesh path.") },
                        confirmButton = { Button(onClick = { getPreferences(0).edit().putBoolean("onboarded", true).apply(); onboarding = false }) { Text("Get started") } }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshInternetStatus()
        if (internetAvailable.value) {
            retryPendingAiWorkflows()
            runCatching { CloudSyncScheduler.enqueue(this) }
            syncCloudOutbox()
            refreshCloudDashboard(false)
        }
    }

    private fun viewCloudMedia(mediaId: String) {
        lifecycleScope.launch {
            try {
                val file = cloudApi.downloadMedia(mediaId)
                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "com.meshlink.app.fileprovider", file)
                val mime = when (file.extension) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; else -> "image/webp" }
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
            } catch (error: Exception) {
                cloudDashboardState.value = cloudDashboardState.value.copy(message = error.message ?: "Could not open attached evidence.", error = true)
            }
        }
    }

    private fun openIncidentMap(latitude: Double, longitude: Double) {
        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude"))) }
            .onFailure { cloudDashboardState.value = cloudDashboardState.value.copy(message = "No map application is available to open this location.", error = true) }
    }

    private fun authenticateCloud(email: String, password: String, displayName: String, register: Boolean) {
        if (!internetAvailable.value || !cloudApi.isConfigured()) {
            cloudDashboardState.value = cloudDashboardState.value.copy(message = "Cloud sign-in requires Online Mode, internet and a configured HTTPS backend.", error = true)
            return
        }
        lifecycleScope.launch {
            cloudDashboardState.value = cloudDashboardState.value.copy(busy = true, message = "Connecting securely…", error = false)
            try {
                val user = cloudApi.authenticate(email, password, displayName, register)
                cloudDashboardState.value = cloudDashboardState.value.copy(user = user, busy = false, message = "Signed in as ${user.role}.", error = false)
                refreshCloudDashboard(false)
                runCatching { CloudSyncScheduler.enqueue(this@MainActivity) }
                syncCloudOutbox()
            } catch (error: Exception) {
                cloudDashboardState.value = cloudDashboardState.value.copy(busy = false, message = error.message ?: "Cloud authentication failed.", error = true)
            }
        }
    }

    private fun refreshCloudDashboard(nearby: Boolean) {
        if (!::cloudApi.isInitialized) return
        lifecycleScope.launch {
            val pending = runCatching { cloudSyncCoordinator.pendingCount() }.getOrDefault(0)
            val shareSos = getPreferences(0).getBoolean("cloud_sync_sos_consent", false)
            val cachedReports = runCatching { cloudSyncCoordinator.cachedIncidents() }.getOrDefault(emptyList()).map {
                CloudIncident(it.incidentId,it.messageId,it.category,it.severity,it.description,it.latitude,it.longitude,it.affectedCount,it.assistance,it.status,it.verification,it.version,it.reportedAt,it.updatedAt,"","Cached report","COMMUNITY",it.mediaId)
            }
            if (!cloudApi.isConfigured()) {
                cloudDashboardState.value = cloudDashboardState.value.copy(incidents = cachedReports, pendingSync = pending, busy = false, shareSos = shareSos, message = "Configure the cloud backend HTTPS URL to enable online coordination. Local reports remain saved.", error = true)
                return@launch
            }
            if (!internetAvailable.value || getSharedPreferences("meshlink_settings", MODE_PRIVATE).getString("app_mode", null) != "online") {
                cloudDashboardState.value = cloudDashboardState.value.copy(incidents = cachedReports, pendingSync = pending, busy = false, nearbyOnly = nearby, shareSos = shareSos, message = "Offline: showing cached incidents. Cloud requests remain queued.", error = false)
                return@launch
            }
            if (!cloudApi.isAuthenticated()) {
                cloudDashboardState.value = cloudDashboardState.value.copy(user = null, incidents = cachedReports, pendingSync = pending, busy = false, nearbyOnly = nearby, shareSos = shareSos, message = "Sign in to view online incidents. Local reports remain available.", error = false)
                return@launch
            }
            cloudDashboardState.value = cloudDashboardState.value.copy(busy = true, nearbyOnly = nearby)
            try {
                val user = cloudApi.me()
                val location = if (nearby) withTimeoutOrNull(5_000L) { runCatching { LocationProvider(this@MainActivity).current() }.getOrNull() } else null
                val incidents = cloudApi.listIncidents(location?.latitude, location?.longitude)
                val coordination = if (user.role == "RESPONDER" || user.role == "ADMIN") runCatching { cloudApi.coordinationSummary() }.getOrNull() else null
                cloudSyncCoordinator.cacheIncidents(incidents)
                cloudDashboardState.value = cloudDashboardState.value.copy(user = user, incidents = incidents, coordinationSummary = coordination, pendingSync = pending, busy = false, nearbyOnly = nearby, shareSos = shareSos, message = if (nearby && location == null) "Location unavailable or permission not granted; showing the latest incidents instead." else "Loaded ${incidents.size} incidents from the secure cloud service.", error = false)
            } catch (error: Exception) {
                val cached = runCatching { cloudSyncCoordinator.cachedIncidents() }.getOrDefault(emptyList()).map {
                    CloudIncident(it.incidentId,it.messageId,it.category,it.severity,it.description,it.latitude,it.longitude,it.affectedCount,it.assistance,it.status,it.verification,it.version,it.reportedAt,it.updatedAt,"","Cached report","COMMUNITY",it.mediaId)
                }
                cloudDashboardState.value = cloudDashboardState.value.copy(user = if (error is com.meshlink.app.core.cloud.CloudApiException && error.statusCode == 401) null else cloudDashboardState.value.user, incidents = cached, pendingSync = pending, busy = false, shareSos = shareSos, message = error.message ?: "Could not load cloud incidents.", error = true)
            }
        }
    }

    private fun submitCommunityReport(category: String, severity: Int, description: String, affectedCount: Int?, assistance: String, locationConsent: Boolean, imageUri: Uri?) {
        lifecycleScope.launch {
            cloudDashboardState.value = cloudDashboardState.value.copy(busy = true, message = "Saving emergency report locally…", error = false)
            var localImage: File? = null
            var reportStaged = false
            try {
                var mime: String? = null
                if (imageUri != null) {
                    mime = contentResolver.getType(imageUri) ?: throw IllegalArgumentException("Could not determine image format.")
                    require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "Only JPEG, PNG and WebP images are supported." }
                    val directory = File(noBackupFilesDir, "cloud_pending_media").apply { mkdirs() }
                    val extension = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; else -> "webp" }
                    localImage = File(directory, "${UUID.randomUUID()}.$extension")
                    contentResolver.openInputStream(imageUri)?.use { input -> localImage!!.outputStream().use { output ->
                        val buffer = ByteArray(8192); var total = 0L
                        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 5L * 1024L * 1024L) { "Image exceeds 5 MB." }; output.write(buffer,0,count) }
                    } } ?: throw IllegalArgumentException("Selected image could not be opened.")
                }
                val location = if (locationConsent) withTimeoutOrNull(5_000L) { runCatching { LocationProvider(this@MainActivity).current() }.getOrNull() } else null
                val messageId = UUID.randomUUID().toString()
                cloudSyncCoordinator.stageCommunityReport(
                    userConsent = true, messageId = messageId, category = category, severity = severity,
                    description = description, latitude = location?.latitude, longitude = location?.longitude,
                    locationConsent = locationConsent, affectedCount = affectedCount, assistance = assistance,
                    mediaId = null, localImagePath = localImage?.absolutePath, imageMimeType = mime, ownerAccountId = cloudApi.accountId()
                )
                reportStaged = true
                cloudSyncCoordinator.cacheLocalReport(messageId, category, severity, description, location?.latitude, location?.longitude, affectedCount)
                runCatching { CloudSyncScheduler.enqueue(this@MainActivity) }
                selectedCloudImageUri.value = null
                selectedCloudImageName.value = ""
                val queued = cloudSyncCoordinator.pendingCount()
                val localReports = cloudSyncCoordinator.cachedIncidents().map { cached ->
                    CloudIncident(cached.incidentId,cached.messageId,cached.category,cached.severity,cached.description,cached.latitude,cached.longitude,cached.affectedCount,cached.assistance,cached.status,cached.verification,cached.version,cached.reportedAt,cached.updatedAt,"","Local report","COMMUNITY",cached.mediaId)
                }
                val locationNote = if (locationConsent && location == null) " GPS was unavailable or permission was not granted, so no coordinates were attached." else ""
                cloudDashboardState.value = cloudDashboardState.value.copy(incidents = localReports, busy = false, pendingSync = queued, message = (if (internetAvailable.value && cloudApi.isAuthenticated() && getSharedPreferences("meshlink_settings", MODE_PRIVATE).getString("app_mode", null) == "online") "Report saved. Uploading with your consent…" else "Report saved locally. It will upload after sign-in and internet return.") + locationNote, error = false)
                if (internetAvailable.value && cloudApi.isAuthenticated() && getSharedPreferences("meshlink_settings", MODE_PRIVATE).getString("app_mode", null) == "online") syncCloudOutbox() else refreshCloudDashboard(false)
            } catch (error: Exception) {
                if (!reportStaged) localImage?.delete()
                cloudDashboardState.value = cloudDashboardState.value.copy(busy = false, message = error.message ?: "Could not save emergency report.", error = true)
            }
        }
    }

    private fun syncCloudOutbox() {
        if (!::cloudApi.isInitialized || !cloudApi.isConfigured() || !cloudApi.isAuthenticated() || !internetAvailable.value || getSharedPreferences("meshlink_settings", MODE_PRIVATE).getString("app_mode", null) != "online") return
        lifecycleScope.launch {
            val result = cloudSyncCoordinator.syncPending(cloudApi)
            val pending = runCatching { cloudSyncCoordinator.pendingCount() }.getOrDefault(0)
            cloudDashboardState.value = cloudDashboardState.value.copy(pendingSync = pending, message = result.fold({ "Synchronized ${it} event(s). ${pending} remain queued." }, { "Cloud sync will retry safely: ${it.message ?: "network or server unavailable"}" }), error = result.isFailure)
            refreshCloudDashboard(false)
        }
    }

    private fun updateCloudIncidentStatus(incident: CloudIncident, status: String) {
        lifecycleScope.launch {
            cloudDashboardState.value = cloudDashboardState.value.copy(busy = true)
            try { cloudApi.updateStatus(incident,status); refreshCloudDashboard(false) }
            catch (error: Exception) { cloudDashboardState.value = cloudDashboardState.value.copy(busy = false,message = error.message ?: "Status update failed.",error = true); refreshCloudDashboard(false) }
        }
    }

    private fun updateCloudIncidentVerification(incident: CloudIncident, verification: String) {
        lifecycleScope.launch {
            cloudDashboardState.value = cloudDashboardState.value.copy(busy = true)
            try { cloudApi.updateVerification(incident,verification); refreshCloudDashboard(false) }
            catch (error: Exception) { cloudDashboardState.value = cloudDashboardState.value.copy(busy = false,message = error.message ?: "Verification update failed.",error = true); refreshCloudDashboard(false) }
        }
    }

    private fun disputeCloudIncident(incident: CloudIncident, reason: String) {
        lifecycleScope.launch {
            cloudDashboardState.value = cloudDashboardState.value.copy(busy = true)
            try { cloudApi.dispute(incident.id,reason); refreshCloudDashboard(false) }
            catch (error: Exception) { cloudDashboardState.value = cloudDashboardState.value.copy(busy = false,message = error.message ?: "Could not dispute report.",error = true) }
        }
    }

    private fun retryPendingAiWorkflows() {
        if (retryingAiQueue || aiBusy.value || getSharedPreferences("meshlink_settings", MODE_PRIVATE).getString("app_mode", null) != "online") return
        retryingAiQueue = true
        aiBusy.value = true
        lifecycleScope.launch {
            try {
                val pending = engine.pendingAiWorkflows()
                for (stored in pending) {
                    var job = stored
                    try {
                        if (!internetAvailable.value) break
                        when (job.stage) {
                            "PRISMA" -> {
                                if (!gnaniVoiceApi.isConfigured()) continue
                                val audio = File(job.audioPath)
                                val transcript = gnaniVoiceApi.transcribe(audio, job.sourceLanguage, job.messageId).text
                                engine.saveVoiceTranscription(job.messageId, transcript, job.sourceLanguage)
                                aiTranscript.value = transcript
                                latestVoiceFile.value = audio
                                latestVoiceMessageId.value = job.messageId
                                engine.advanceAiWorkflow(job.jobId, "EVON", transcript, job.targetLanguage, job.targetVoice)
                                job = job.copy(stage = "EVON", sourceText = transcript)
                            }
                        }
                        when (job.stage) {
                            "EVON" -> {
                                if (!evonApiClient.isConfigured() || job.sourceText.isBlank()) continue
                                val analysis = evonApiClient.analyzeEmergency(job.sourceText, job.messageId)
                                engine.saveEmergencyAnalysis(job.messageId, analysis)
                                evonAnswer.value = analysis.suggestedResponse
                                emergencyAnalysis.value = "Emergency: ${analysis.emergencyType}\nSeverity: ${analysis.severity}\nLocation: ${analysis.locationMentioned ?: "Not stated"}\nPeople affected: ${analysis.peopleAffected ?: "Not stated"}\nImmediate assistance: ${analysis.immediateAssistance}\nInstructions: ${analysis.instructions.joinToString("; ")}\nWarnings: ${analysis.warnings.joinToString("; ")}"
                                engine.clearAiWorkflowJob(job.jobId)
                            }
                            "TRANSLATE_REPORT", "TRANSLATE_RESPONSE" -> {
                                if (!translationClient.isConfigured()) continue
                                val result = translationClient.translate(job.sourceText, job.targetLanguage, job.sourceLanguage)
                                val kind = if (job.stage == "TRANSLATE_REPORT") "report" else "response"
                                engine.saveEmergencyTranslation(job.messageId, result, kind)
                                if (kind == "report") translatedTranscript.value = result.translatedText else translatedResponse.value = result.translatedText
                                engine.clearAiWorkflowJob(job.jobId)
                            }
                            "TIMBRE" -> {
                                if (!gnaniVoiceApi.isConfigured()) continue
                                val result = gnaniVoiceApi.synthesize(job.sourceText, job.targetLanguage, job.targetVoice, job.messageId)
                                engine.saveGeneratedSpeech(result.messageId, result.file, result.language, result.voice, result.model)
                                aiAudioPath.value = result.file.absolutePath
                                aiAudioMessageId.value = result.messageId
                                engine.clearAiWorkflowJob(job.jobId)
                            }
                            else -> engine.clearAiWorkflowJob(job.jobId)
                        }
                    } catch (error: Exception) {
                        if (error is com.meshlink.app.core.ai.GnaniApiException && error.error == com.meshlink.app.core.ai.GnaniApiError.NETWORK) {
                            engine.advanceAiWorkflow(job.jobId, job.stage, job.sourceText, job.targetLanguage, job.targetVoice, error.message)
                            aiStatus.value = "A queued AI task remains pending until connectivity returns."
                        } else {
                            engine.failAiWorkflow(job.jobId, error.message)
                            aiStatus.value = "A queued AI task needs attention: ${error.message ?: "provider rejected the request"}"
                        }
                    }
                }
            } finally { retryingAiQueue = false; aiBusy.value = false }
        }
    }

    private fun importVoiceRecording(uri: Uri) {
        lifecycleScope.launch {
            var importedFile: File? = null
            try {
                val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                val mime = contentResolver.getType(uri).orEmpty().lowercase()
                val extension = (displayName?.substringAfterLast('.', "")?.lowercase()).orEmpty().ifBlank {
                    when (mime) {
                        "audio/mp4", "audio/m4a" -> "m4a"
                        "audio/wav", "audio/x-wav" -> "wav"
                        "audio/mpeg" -> "mp3"
                        "audio/aac" -> "aac"
                        "audio/ogg" -> "ogg"
                        "audio/flac" -> "flac"
                        else -> ""
                    }
                }
                if (extension !in setOf("m4a", "mp4", "wav", "mp3", "aac", "ogg", "flac")) {
                    aiStatus.value = "Unsupported audio file. Choose WAV, MP3, OGG, FLAC, AAC or M4A."
                    return@launch
                }
                val directory = File(filesDir, "voice_messages").apply { mkdirs() }
                val destination = File(directory, "import_${UUID.randomUUID()}.$extension")
                importedFile = destination
                contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 25L * 1024L * 1024L) throw IllegalArgumentException("Audio file exceeds MeshLink's 25 MB local safety limit.")
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw IllegalArgumentException("Could not open the selected audio file.")
                if (!destination.exists() || destination.length() == 0L) throw IllegalArgumentException("Selected audio file is empty.")
                val id = UUID.randomUUID().toString()
                engine.registerLocalVoiceAsset(id, destination)
                latestVoiceFile.value = destination
                latestVoiceMessageId.value = id
                aiTranscript.value = ""
                evonAnswer.value = ""
                emergencyAnalysis.value = ""
                translatedTranscript.value = ""
                translatedResponse.value = ""
                aiAudioPath.value = null
                aiAudioMessageId.value = ""
                aiStatus.value = "Audio selected and stored locally. Ready for Prisma transcription."
            } catch (error: Exception) {
                importedFile?.let { runCatching { it.delete() } }
                aiStatus.value = error.message ?: "Could not import the selected audio file."
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        }
        voicePlayer.stop()
        super.onDestroy()
    }

    private fun toggleMesh(enabled: Boolean) {
        if (!enabled) { MeshForegroundService.stop(this); return }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val permissions = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) startMeshAfterBluetoothCheck()
            else {
                val request = missing + if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
                blePermissions.launch(request.toTypedArray())
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startMeshAfterBluetoothCheck()
        else blePermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private fun requestLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            if (pendingLocation) { pendingLocation = false; lifecycleScope.launch { engine.sendLocation() } }
        } else locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

private enum class AppMode(val storageValue: String, val label: String) {
    ONLINE("online", "Online"),
    OFFLINE("offline", "Offline");

    companion object {
        fun from(value: String?): AppMode? = entries.firstOrNull { it.storageValue == value }
    }
}

@Composable
private fun ModeSelectionScreen(
    internetAvailable: Boolean,
    onSelect: (AppMode) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(14.dp))
            Text("MeshLink 2.0", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Choose your communication mode", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Online Mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Use internet-enabled services when they are configured. Nearby BLE messaging and emergency communication remain available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (internetAvailable) "Internet connection: available" else "Internet connection: unavailable",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (internetAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Button(onClick = { onSelect(AppMode.ONLINE) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue in Online Mode")
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Offline Mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Communicate locally through the existing Bluetooth mesh. Internet access is not required for nearby emergency messaging.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { onSelect(AppMode.OFFLINE) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue in Offline Mode")
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "Bluetooth mesh is independent of internet connectivity. Losing internet does not switch off local BLE communication.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

@Composable
private fun MeshLinkScreen(
    state: MeshUiState,
    appMode: AppMode,
    internetAvailable: Boolean,
    onChangeMode: () -> Unit,
    messages: List<MessageEntity>,
    events: List<EventEntity>,
    safety: List<MessageEntity>,
    latestVoiceFile: File?,
    latestVoiceMessageId: String,
    onSelectVoiceFile: () -> Unit,
    aiRecording: Boolean,
    onStartAiRecording: () -> Unit,
    onStopAiRecording: () -> Unit,
    gnaniConfigured: Boolean,
    evonConfigured: Boolean,
    translationConfigured: Boolean,
    evonAnswer: String,
    emergencyAnalysis: String,
    translatedTranscript: String,
    translatedResponse: String,
    onTranslateEmergency: (String, String, String, String, String) -> Unit,
    onEvonAnalyze: (String, String) -> Unit,
    onRetryQueued: () -> Unit,
    onShareEmergencySummary: (String) -> Unit,
    onEvonAsk: (String) -> Unit,
    aiBusy: Boolean,
    aiStatus: String,
    aiTranscript: String,
    aiAudioPath: String?,
    aiAudioMessageId: String,
    aiPlaybackState: VoicePlaybackState,
    onPrismaTranscribe: (File, String, String) -> Unit,
    onTimbreSynthesize: (String, String, String, String) -> Unit,
    onPlayAiAudio: (String) -> Unit,
    onPauseAiAudio: () -> Unit,
    onResumeAiAudio: () -> Unit,
    onReplayAiAudio: (String) -> Unit,
    onMeshToggle: (Boolean) -> Unit,
    onText: (MeshPeer, String) -> Unit,
    onBroadcast: (String) -> Unit,
    onSubmitCoordinationRecord: (String, String, Boolean) -> Unit,
    onRetryMessage: (String) -> Unit,
    onAlert: (String) -> Unit,
    onLocation: () -> Unit,
    onSos: (String, File?, String?, Boolean, String?) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: (MeshPeer?) -> Unit,
    onSosVoiceStart: () -> Unit,
    onSosVoiceStop: () -> File?,
    onPlayVoice: () -> Unit,
    onPlayVoicePath: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onAvatarChange: (Int) -> Unit,
    onSafetyReview: (String, SafetyDecision) -> Unit,
    onSosReview: (String, String) -> Unit,
    cloudState: CloudDashboardState,
    cloudConfigured: Boolean,
    selectedCloudImageUri: Uri?,
    selectedCloudImageName: String,
    onCloudAuthenticate: (String, String, String, Boolean) -> Unit,
    onCloudLogout: () -> Unit,
    onCloudRefresh: (Boolean) -> Unit,
    onCloudSubmit: (String, Int, String, Int?, String, Boolean, Uri?) -> Unit,
    onCloudPickImage: () -> Unit,
    onCloudStatus: (CloudIncident, String) -> Unit,
    onCloudVerification: (CloudIncident, String) -> Unit,
    onCloudDispute: (CloudIncident, String) -> Unit,
    onCloudSync: () -> Unit,
    onCloudSosConsent: (Boolean) -> Unit,
    onCloudOpenMap: (Double, Double) -> Unit,
    onCloudViewMedia: (String) -> Unit,
    playingVoicePath: String? = null
) {
    var selected by remember { mutableStateOf<MeshPeer?>(null) }
    // Internal screen IDs remain stable so secondary screens keep their existing state.
    // Only the four primary destinations are shown in the bottom navigation.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(state.localName) { mutableStateOf(state.localName) }
    var confirmSos by remember { mutableStateOf(false) }
    var showAlert by remember { mutableStateOf(false) }
    var alertText by remember { mutableStateOf("") }
    var sosMessage by remember { mutableStateOf("") }
    var sosCategory by rememberSaveable { mutableStateOf("MEDICAL") }
    var sosSeverity by rememberSaveable { mutableIntStateOf(4) }
    var sosShareLocation by rememberSaveable { mutableStateOf(false) }
    var sosDestinationNodeId by rememberSaveable { mutableStateOf("*") }
    var sosVoice by remember { mutableStateOf<File?>(null) }
    var sosRecording by remember { mutableStateOf(false) }
    var groupChat by remember { mutableStateOf(false) }
    var avatarDraft by remember(state.localAvatarIndex) { mutableIntStateOf(state.localAvatarIndex) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (tab == 1 && (selected != null || groupChat)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(if (groupChat) "MG" else selected!!.displayName, 38.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(if (groupChat) "Mesh Group" else selected!!.displayName, fontWeight = FontWeight.SemiBold)
                                Text(if (groupChat) "${state.peers.size} authenticated peers" else if (state.active) "mesh connected" else "mesh offline", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = { selected = null; groupChat = false }) { Icon(Icons.Default.ArrowBack, "Back") } }
                )
            } else {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    title = {
                        Column {
                            Text("MeshLink", fontWeight = FontWeight.Bold)
                            Text(
                                "${appMode.label}  •  Internet ${if (internetAvailable) "✓" else "×"}  •  BLE ${when { state.bleStatus.contains("OFF", true) -> "OFF"; state.bleStatus.contains("active", true) -> "READY"; state.active -> "STARTING"; else -> "STOPPED" }}",
                                fontSize = 10.sp,
                                color = if (state.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = onChangeMode) { Text("Mode") }
                        Box {
                            IconButton(onClick = { moreMenuExpanded = true }) {
                                BadgedBox(badge = { if (safety.isNotEmpty()) Badge { Text(safety.size.coerceAtMost(99).toString()) } }) {
                                    Icon(Icons.Default.MoreVert, "More destinations and settings")
                                }
                            }
                            DropdownMenu(expanded = moreMenuExpanded, onDismissRequest = { moreMenuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Emergency alerts / SOS") },
                                    leadingIcon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { moreMenuExpanded = false; tab = 3 }
                                )
                                DropdownMenuItem(
                                    text = { Text("Incident reports") },
                                    leadingIcon = { Icon(Icons.Default.Assignment, null) },
                                    onClick = { moreMenuExpanded = false; selected = null; groupChat = false; tab = 5 }
                                )
                                DropdownMenuItem(
                                    text = { Text("Gnani voice processing") },
                                    leadingIcon = { Icon(Icons.Default.Mic, null) },
                                    onClick = { moreMenuExpanded = false; selected = null; groupChat = false; tab = 7 }
                                )
                                DropdownMenuItem(
                                    text = { Text("Profile & settings") },
                                    leadingIcon = { Icon(Icons.Default.Person, null) },
                                    onClick = { moreMenuExpanded = false; selected = null; groupChat = false; tab = 6 }
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Mesh", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(checked = state.active, onCheckedChange = onMeshToggle)
                        }
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                val destinations = listOf(
                    0 to ("Home" to Icons.Default.Home),
                    1 to ("Chats" to Icons.Default.Chat),
                    2 to ("Nearby" to Icons.Default.LocationOn),
                    4 to ("Response" to Icons.Default.HealthAndSafety)
                )
                destinations.forEach { (screen, item) ->
                    val (label, icon) = item
                    NavigationBarItem(
                        selected = tab == screen,
                        onClick = {
                            tab = screen
                            if (screen != 1) { selected = null; groupChat = false }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        label = { Text(label, fontWeight = if (tab == screen) FontWeight.SemiBold else FontWeight.Normal) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(
                    onClick = { selected = null; groupChat = false; tab = 3 },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Open emergency SOS")
                    Spacer(Modifier.width(6.dp))
                    Text("SOS", fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (appMode == AppMode.ONLINE && !internetAvailable) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (state.active) "Internet unavailable — continuing over the local BLE mesh."
                        else "Internet unavailable — enable Mesh to use local BLE communication.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            when (tab) {
                0 -> Home(
                    state = state, appMode = appMode, internetAvailable = internetAvailable,
                    messages = messages, pendingSync = cloudState.pendingSync,
                    cloudIncidents = cloudState.incidents, cloudConfigured = cloudConfigured, cloudSignedIn = cloudState.user != null,
                    gnaniConfigured = gnaniConfigured, evonConfigured = evonConfigured, translationConfigured = translationConfigured,
                    onSosReview = onSosReview, onOpenLocation = onCloudOpenMap,
                    onSos = { tab = 3 }, onAlert = { showAlert = true }, onLocation = onLocation,
                    onVoice = { tab = 4 }, onOpenChats = { tab = 1 }, onOpenNearby = { tab = 2 },
                    onOpenAi = { tab = 4 }, onOpenReports = { tab = 5 }
                )
                1 -> Chat(messages, state.peers, state.discoveredPeers, selected, groupChat, { selected = null; groupChat = false }, { selected = it; groupChat = false }, { groupChat = true; selected = null }, text, { text = "" }, onText, onBroadcast, { text = it }, onVoiceStart, { peer -> onVoiceStop(if (groupChat) null else peer) }, recording, { recording = it }, state.lastVoicePath, onPlayVoicePath, onLocation, onRetryMessage, { selected = null; groupChat = false; tab = 3 }, playingVoicePath)
                2 -> MapTab(state)
                3 -> EmergencyCenter(sosCategory, { sosCategory = it }, sosSeverity, { sosSeverity = it }, sosMessage, { sosMessage = it }, sosShareLocation, { sosShareLocation = it }, state.peers, sosDestinationNodeId, { sosDestinationNodeId = it }, { confirmSos = true })
                4 -> DisasterResponseWorkspace(
                    connectedPeers = state.peers.size,
                    online = internetAvailable,
                    onSubmit = onSubmitCoordinationRecord,
                    onOpenReports = { tab = 5 },
                    onOpenVoiceTools = { tab = 7 }
                )
                7 -> GnaniVoiceAssistant(
                    latestVoiceFile = latestVoiceFile, latestVoiceMessageId = latestVoiceMessageId,
                    onSelectVoiceFile = onSelectVoiceFile, aiRecording = aiRecording,
                    onStartAiRecording = onStartAiRecording, onStopAiRecording = onStopAiRecording,
                    onlineMode = appMode == AppMode.ONLINE, configured = gnaniConfigured,
                    evonConfigured = evonConfigured, translationConfigured = translationConfigured,
                    evonAnswer = evonAnswer, emergencyAnalysis = emergencyAnalysis,
                    translatedTranscript = translatedTranscript, translatedResponse = translatedResponse,
                    onTranslateEmergency = onTranslateEmergency, onEvonAnalyze = onEvonAnalyze, onSendToMesh = onBroadcast,
                    onRetryQueued = onRetryQueued, onShareEmergencySummary = onShareEmergencySummary,
                    onEvonAsk = onEvonAsk, internetAvailable = internetAvailable, busy = aiBusy,
                    status = aiStatus, transcript = aiTranscript, audioPath = aiAudioPath,
                    audioMessageId = aiAudioMessageId, audioPlaybackState = aiPlaybackState,
                    activePlaybackPath = playingVoicePath, onTranscribe = onPrismaTranscribe,
                    onSynthesize = onTimbreSynthesize, onPlayAudio = onPlayAiAudio,
                    onPauseAudio = onPauseAiAudio, onResumeAudio = onResumeAiAudio,
                    onReplayAudio = onReplayAiAudio
                )
                5 -> CloudDashboard(
                    state = cloudState, onlineMode = appMode == AppMode.ONLINE,
                    internetAvailable = internetAvailable, configured = cloudConfigured,
                    onAuthenticate = onCloudAuthenticate, onLogout = onCloudLogout,
                    onRefresh = onCloudRefresh, onSubmitReport = onCloudSubmit,
                    onPickImage = onCloudPickImage, selectedImageName = selectedCloudImageName,
                    selectedImageUri = selectedCloudImageUri, onStatus = onCloudStatus,
                    onVerification = onCloudVerification, onDispute = onCloudDispute,
                    onSync = onCloudSync, onShareSosConsent = onCloudSosConsent,
                    onOpenMap = onCloudOpenMap, onViewMedia = onCloudViewMedia
                )
                6 -> ProfileHub(
                    state, events, safety, cloudState, appMode, internetAvailable,
                    gnaniConfigured, evonConfigured, translationConfigured, cloudConfigured,
                    onChangeMode, { editingName = true }, onMeshToggle, onSafetyReview, onCloudLogout
                )
            }
        }
    }

    if (confirmSos) {
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Send SOS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Send a priority emergency packet over the authenticated local mesh. Cloud and AI services are not involved.", fontSize = 12.sp)
                    Text("$sosCategory • Severity $sosSeverity/5", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    Text("Send to", fontWeight = FontWeight.SemiBold)
                    var sosTargetMenu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { sosTargetMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (sosDestinationNodeId == "*") "All authenticated MeshLink peers (group)" else state.peers.firstOrNull { it.nodeId == sosDestinationNodeId }?.displayName ?: "Selected peer", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ExpandMore, null)
                        }
                        DropdownMenu(expanded = sosTargetMenu, onDismissRequest = { sosTargetMenu = false }) {
                            DropdownMenuItem(text = { Text("All authenticated peers · Group") }, onClick = { sosDestinationNodeId = "*"; sosTargetMenu = false })
                            state.peers.forEach { peer ->
                                DropdownMenuItem(text = { Text("${peer.displayName} · Personal") }, onClick = { sosDestinationNodeId = peer.nodeId; sosTargetMenu = false })
                            }
                        }
                    }
                    if (state.peers.isEmpty()) Text("No authenticated peers are connected. SOS will be saved in the queue on this phone and cannot be confirmed delivered yet.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = sosShareLocation, onCheckedChange = { sosShareLocation = it }); Text("Include my GPS location (optional)", fontSize = 12.sp) }
                    OutlinedTextField(
                        value = sosMessage,
                        onValueChange = { sosMessage = it.take(500) },
                        label = { Text("Emergency message") },
                        placeholder = { Text("What happened?") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            if (!sosRecording) { sosRecording = true; onSosVoiceStart() }
                            else { sosRecording = false; sosVoice = onSosVoiceStop() }
                        }) {
                            Icon(if (sosRecording) Icons.Default.Stop else Icons.Default.Mic, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (sosRecording) "Stop voice" else "Attach voice")
                        }
                        Spacer(Modifier.width(8.dp))
                        if (sosVoice != null) Text("Voice attached", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val msg = sosMessage.trim().ifBlank { "Emergency assistance requested" }
                    confirmSos = false
                    val payload = "[$sosCategory • severity $sosSeverity/5] $msg"
                    onSos(payload, sosVoice, if (sosVoice != null) latestVoiceMessageId else null, sosShareLocation, sosDestinationNodeId.takeIf { it != "*" })
                    sosMessage = ""
                    sosVoice = null
                    sosRecording = false
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Send SOS") }
            },
            dismissButton = { TextButton(onClick = { confirmSos = false; sosRecording = false; sosVoice = null }) { Text("Cancel") } }
        )
    }

    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            icon = { Icon(Icons.Default.Campaign, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Send local alert") },
            text = {
                OutlinedTextField(
                    value = alertText,
                    onValueChange = { alertText = it.take(240) },
                    label = { Text("Alert message") },
                    placeholder = { Text("e.g. Road blocked near the main gate") },
                    minLines = 2,
                    maxLines = 5
                )
            },
            confirmButton = {
                Button(onClick = {
                    val message = alertText.trim()
                    if (message.isNotEmpty()) { onAlert(message); alertText = ""; showAlert = false }
                }) { Text("Send alert") }
            },
            dismissButton = { TextButton(onClick = { showAlert = false }) { Text("Cancel") } }
        )
    }

    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Your MeshLink profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = nameDraft, onValueChange = { nameDraft = it.take(32) }, singleLine = true, label = { Text("Display name") }, leadingIcon = { Icon(Icons.Default.Person, null) })
                    Text("Choose an avatar", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        AvatarOptions.forEachIndexed { index, _ ->
                            Box(Modifier.clickable { avatarDraft = index }.padding(2.dp)) {
                                Avatar(nameDraft, 40.dp, index, avatarDraft == index)
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { onNameChange(nameDraft); onAvatarChange(avatarDraft); editingName = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun GnaniVoiceAssistant(
    latestVoiceFile: File?,
    latestVoiceMessageId: String,
    onSelectVoiceFile: () -> Unit,
    aiRecording: Boolean,
    onStartAiRecording: () -> Unit,
    onStopAiRecording: () -> Unit,
    onlineMode: Boolean,
    configured: Boolean,
    evonConfigured: Boolean,
    translationConfigured: Boolean,
    evonAnswer: String,
    emergencyAnalysis: String,
    translatedTranscript: String,
    translatedResponse: String,
    onTranslateEmergency: (String, String, String, String, String) -> Unit,
    onEvonAnalyze: (String, String) -> Unit,
    onSendToMesh: (String) -> Unit,
    onRetryQueued: () -> Unit,
    onShareEmergencySummary: (String) -> Unit,
    onEvonAsk: (String) -> Unit,
    internetAvailable: Boolean,
    busy: Boolean,
    status: String,
    transcript: String,
    audioPath: String?,
    audioMessageId: String,
    audioPlaybackState: VoicePlaybackState,
    activePlaybackPath: String?,
    onTranscribe: (File, String, String) -> Unit,
    onSynthesize: (String, String, String, String) -> Unit,
    onPlayAudio: (String) -> Unit,
    onPauseAudio: () -> Unit,
    onResumeAudio: () -> Unit,
    onReplayAudio: (String) -> Unit
) {
    var prismaLanguage by remember { mutableStateOf("hi-IN") }
    var ttsLanguage by remember { mutableStateOf("hi-IN") }
    var voice by remember { mutableStateOf("Nalini") }
    var languageMenu by remember { mutableStateOf(false) }
    var prismaLanguageMenu by remember { mutableStateOf(false) }
    var voiceMenu by remember { mutableStateOf(false) }
    var responseText by remember { mutableStateOf("") }
    var targetLanguage by remember { mutableStateOf("hi") }
    var targetLanguageMenu by remember { mutableStateOf(false) }
    var evonPrompt by remember { mutableStateOf("") }
    var confirmShareSummary by remember { mutableStateOf(false) }
    var responseMessageId by remember { mutableStateOf("") }
    var transcriptDraft by remember(transcript) { mutableStateOf(transcript) }
    var selectedGuide by rememberSaveable { mutableStateOf("Medical emergency") }
    var translationSource by rememberSaveable { mutableStateOf("") }
    var localTtsStatus by remember { mutableStateOf("Preparing phone speech engine…") }
    val context = LocalContext.current
    val localTts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) { result ->
            localTtsStatus = if (result == TextToSpeech.SUCCESS) "Phone speech engine ready; offline language data depends on this device." else "Phone speech engine is unavailable on this device."
        }
        localTts.value = engine
        onDispose {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
            localTts.value = null
        }
    }
    fun speakOnPhone(text: String, languageTag: String) {
        val engine = localTts.value
        if (engine == null || text.isBlank()) {
            localTtsStatus = "Phone speech engine is still starting or unavailable."
            return
        }
        val languageResult = runCatching { engine.setLanguage(Locale.forLanguageTag(languageTag)) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            localTtsStatus = "This phone has no installed voice data for $languageTag. Install it in Android Text-to-speech settings."
            return
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "meshlink-local-${System.currentTimeMillis()}")
        localTtsStatus = if (result == TextToSpeech.SUCCESS) "Speaking with the phone's installed voice. Offline playback depends on installed voice data." else "The phone could not start speech playback."
    }
    val clipboard = LocalClipboardManager.current
    val originalDurationMs = remember(latestVoiceFile?.absolutePath) { readAudioDurationMs(latestVoiceFile) }
    val availableVoices = TimbreCatalog.voicesFor(ttsLanguage)
    fun shareResult(value: String) {
        if (value.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share MeshLink result")) }
    }
    val isAiAudioActive = audioPath != null && activePlaybackPath == audioPath
    if (confirmShareSummary) {
        AlertDialog(
            onDismissRequest = { confirmShareSummary = false },
            title = { Text("Share emergency summary?") },
            text = { Text("This sends the AI-generated advisory summary to nearby MeshLink peers over the local mesh. It does not trigger SOS or contact emergency services. Review the details before continuing.") },
            confirmButton = { Button(onClick = { confirmShareSummary = false; onShareEmergencySummary(emergencyAnalysis) }) { Text("Share with mesh") } },
            dismissButton = { TextButton(onClick = { confirmShareSummary = false }) { Text("Cancel") } }
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Emergency voice assistant", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Prisma transcription → Evon analysis → Translation → Timbre speech. Each stage is independent; local BLE and SOS remain available without AI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HealthAndSafety, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text("Offline emergency quick guide", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text("Use this immediately, even without API keys or internet. In India, 112 is the unified emergency number for urgent police, fire, medical and other immediate threats. Select the situation below for practical first steps; use the SOS action to alert MeshLink peers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    var guideMenu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { guideMenu = true }) { Text(selectedGuide) }
                        DropdownMenu(expanded = guideMenu, onDismissRequest = { guideMenu = false }) {
                            listOf("Medical emergency", "Road accident", "Fire or smoke", "Flood or severe weather", "Violence or personal safety", "Earthquake", "Lost or separated", "Other emergency").forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = { selectedGuide = option; guideMenu = false })
                            }
                        }
                    }
                    val guide = when (selectedGuide) {
                        "Road accident" -> listOf("Move yourself to a safe place away from traffic.", "Call 112 and share the exact road/location landmark.", "Do not move an injured person unless there is immediate danger.", "Do not remove a helmet; keep the person still and monitor breathing.")
                        "Fire or smoke" -> listOf("Leave the building using stairs; do not use lifts.", "Stay low under smoke and do not re-enter the building.", "Call 112 from a safe location and give the address.", "If trapped, close doors between you and smoke and signal for help.")
                        "Unsafe situation" -> listOf("Move toward a well-lit, populated safe place if possible.", "Call 112 when in immediate danger.", "Share your location only with trusted contacts/responders.", "Avoid confronting the threatening person; preserve evidence only if safe.")
                        "Flood or severe weather" -> listOf("Move to higher ground and stay away from flowing or electrical water.", "Do not walk or drive through flood water.", "Follow official evacuation instructions and avoid downed power lines.", "Send your location and the number of people needing help to trusted contacts.")
                        "Violence or personal safety" -> listOf("Move away from the threat toward a populated, secure place if possible.", "Call 112 when there is immediate danger.", "Do not confront the person; ask a trusted person to stay on the phone.", "Share your location with trusted contacts only when safe.")
                        "Earthquake" -> listOf("Drop, cover and hold on during shaking; protect your head and neck.", "Stay away from windows and heavy objects.", "After shaking stops, use stairs and move to an open area if the building is damaged.", "Expect aftershocks and follow official safety instructions.")
                        "Lost or separated" -> listOf("Stay in a safe, visible public place.", "Call 112 if a child, vulnerable person or anyone is in danger.", "Share a clear landmark and your location with trusted contacts.", "Keep your phone available and agree on one safe meeting point.")
                        "Other emergency" -> listOf("Move away from immediate danger and check whether the scene is safe.", "Call 112 in India for urgent emergency response.", "Tell responders what happened, exact location, number of people affected and immediate hazards.", "Use MeshLink SOS to alert connected peers; delivery requires an authenticated mesh connection.")
                        else -> listOf("Check that the scene is safe before approaching.", "Call 112 for urgent medical help.", "If the person is unconscious or not breathing normally, follow the emergency operator's instructions.", "Control severe external bleeding with firm direct pressure if safe; do not remove embedded objects.")
                    }
                    guide.forEachIndexed { index, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("${index + 1}.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(7.dp))
                            Text(step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Text("Emergency phrasebook · works without translation API", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    val phrases = listOf(
                        "I need emergency help. Please call 112." to "मुझे आपातकालीन मदद चाहिए। कृपया 112 पर कॉल करें।",
                        "Someone is injured. Please send an ambulance." to "कोई घायल है। कृपया एम्बुलेंस भेजें।",
                        "There is a fire. Please move to a safe place." to "आग लगी है। कृपया सुरक्षित जगह पर जाएँ।",
                        "Please tell me your location." to "कृपया मुझे अपना स्थान बताइए।"
                    )
                    phrases.forEach { (english, hindi) ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)).padding(10.dp)) {
                            Text(english, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(hindi, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { responseText = english }) { Text("Use English") }
                                TextButton(onClick = { responseText = hindi }) { Text("Use Hindi") }
                                TextButton(onClick = { responseText = english; speakOnPhone(english, "en-IN") }) { Text("Speak EN") }
                                TextButton(onClick = { responseText = hindi; speakOnPhone(hindi, "hi-IN") }) { Text("Speak HI") }
                                TextButton(onClick = { onSendToMesh(english) }) { Text("Send EN to group") }
                                TextButton(onClick = { onSendToMesh(hindi) }) { Text("Send HI to group") }
                            }
                        }
                    }
                    Text("Phrasebook text is fixed local wording, not live AI translation. Confirm that the listener understands it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(localTtsStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = if (busy) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Text(if (busy) "Processing" else "Workflow status", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        status.ifBlank {
                            if (!onlineMode) "Offline mode: choose and preview audio locally. Hosted AI processing is paused."
                            else if (!internetAvailable) "Internet unavailable. Your local audio is preserved; hosted stages cannot run."
                            else if (!configured) "Prisma/Timbre credentials are not configured."
                            else "Choose a recording to begin. No transcription or analysis is fabricated."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Prisma · Speech to text", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        latestVoiceFile?.let {
                            "Selected audio: ${it.name}\nSize: ${"%.1f".format(it.length() / 1024.0)} KB • Duration: ${formatAudioDuration(originalDurationMs)}\nMessage ID: $latestVoiceMessageId"
                        } ?: "Record a voice note from Mesh or Chats, or select an audio file.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (latestVoiceFile?.isFile == true) {
                        OutlinedButton(
                            onClick = { onPlayAudio(latestVoiceFile.absolutePath) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (activePlaybackPath == latestVoiceFile.absolutePath) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (activePlaybackPath == latestVoiceFile.absolutePath) "Replay original audio" else "Preview original audio")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { if (aiRecording) onStopAiRecording() else onStartAiRecording() }, enabled = onlineMode && !busy, modifier = Modifier.weight(1f)) {
                            Icon(if (aiRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                            Spacer(Modifier.width(5.dp)); Text(if (aiRecording) "Stop recording" else "Record voice")
                        }
                        OutlinedButton(onClick = onSelectVoiceFile, enabled = onlineMode && !busy && !aiRecording, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(5.dp)); Text("Choose audio")
                        }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { prismaLanguageMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Spoken language: ${prismaLanguage}") }
                        DropdownMenu(expanded = prismaLanguageMenu, onDismissRequest = { prismaLanguageMenu = false }) {
                            listOf("bn-IN", "en-IN", "gu-IN", "hi-IN", "kn-IN", "ml-IN", "mr-IN", "pa-IN", "ta-IN", "te-IN").forEach { code ->
                                DropdownMenuItem(text = { Text(code) }, onClick = { prismaLanguage = code; prismaLanguageMenu = false })
                            }
                        }
                    }
                    Button(
                        onClick = { latestVoiceFile?.let { onTranscribe(it, latestVoiceMessageId, prismaLanguage.trim()) } },
                        enabled = latestVoiceFile?.isFile == true && onlineMode && configured && internetAvailable && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (transcript.isBlank()) "Transcribe selected audio" else "Retry transcription") }
                    if (transcript.isNotBlank()) {
                        Text("Transcription · source language: $prismaLanguage", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = transcriptDraft,
                            onValueChange = { transcriptDraft = it.take(12000) },
                            label = { Text("Review and edit transcript") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(transcriptDraft)) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(5.dp)); Text("Copy")
                            }
                            OutlinedButton(onClick = { shareResult(transcriptDraft) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Share, null); Spacer(Modifier.width(5.dp)); Text("Share")
                            }
                            TextButton(onClick = { responseText = transcriptDraft }) { Text("Use for response") }
                            TextButton(onClick = { onSendToMesh(transcriptDraft.trim()) }) { Text("Send to Mesh group") }
                        }
                    }
                    if (emergencyAnalysis.isNotBlank()) {
                        Text("Extracted emergency information · AI-generated", fontWeight = FontWeight.SemiBold)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                            Text(emergencyAnalysis, modifier = Modifier.fillMaxWidth().padding(12.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(emergencyAnalysis)) }) { Text("Copy analysis") }
                            TextButton(onClick = { shareResult(emergencyAnalysis) }) { Text("Share outside app") }
                            TextButton(onClick = { onSendToMesh(emergencyAnalysis) }) { Text("Send to Mesh group") }
                        }
                    }
                    if (translatedTranscript.isNotBlank()) {
                        Text("Translated original report · target language: $targetLanguage", fontWeight = FontWeight.SemiBold)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                            Text(translatedTranscript, modifier = Modifier.fillMaxWidth().padding(12.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(translatedTranscript)) }) { Text("Copy translation") }
                            TextButton(onClick = { shareResult(translatedTranscript) }) { Text("Share outside app") }
                            TextButton(onClick = { onSendToMesh(translatedTranscript) }) { Text("Send to Mesh group") }
                        }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Multilingual emergency translation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Use this to communicate an emergency report or reviewed instruction to someone who speaks another language. Enter or reuse text, translate it, review the result, then send it to nearby MeshLink peers or speak it aloud.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = translationSource,
                        onValueChange = { translationSource = it.take(6000) },
                        label = { Text("Emergency message to translate") },
                        placeholder = { Text("Example: Someone is injured near the main gate. Please send help.") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { translationSource = transcriptDraft }) { Text("Use reviewed transcript") }
                        TextButton(onClick = { translationSource = responseText }) { Text("Use response text") }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box {
                            OutlinedButton(onClick = { targetLanguageMenu = true }) { Text("Target language: $targetLanguage") }
                            DropdownMenu(expanded = targetLanguageMenu, onDismissRequest = { targetLanguageMenu = false }) {
                                listOf("en" to "English", "hi" to "Hindi", "bn" to "Bengali", "te" to "Telugu", "ta" to "Tamil", "mr" to "Marathi", "gu" to "Gujarati", "kn" to "Kannada", "ml" to "Malayalam", "pa" to "Punjabi").forEach { (code, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { targetLanguage = code; targetLanguageMenu = false })
                                }
                            }
                        }
                        Text("Google Cloud Translation sends selected text to Google. Machine translation must be checked by a person before emergency use.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = {
                        if (responseMessageId.isBlank()) responseMessageId = UUID.randomUUID().toString()
                        onTranslateEmergency(translationSource.trim(), prismaLanguage, targetLanguage, latestVoiceMessageId.ifBlank { responseMessageId }, "manual")
                    }, enabled = translationSource.isNotBlank() && onlineMode && internetAvailable && translationConfigured && !busy, modifier = Modifier.fillMaxWidth()) { Text("Translate entered emergency message") }
                    Button(onClick = { onTranslateEmergency(transcriptDraft, prismaLanguage, targetLanguage, latestVoiceMessageId, "report") }, enabled = transcript.isNotBlank() && latestVoiceMessageId.isNotBlank() && onlineMode && internetAvailable && translationConfigured && !busy, modifier = Modifier.fillMaxWidth()) { Text("Translate original voice report") }
                    Button(onClick = { onTranslateEmergency(evonAnswer, prismaLanguage, targetLanguage, latestVoiceMessageId, "response") }, enabled = evonAnswer.isNotBlank() && latestVoiceMessageId.isNotBlank() && onlineMode && internetAvailable && translationConfigured && !busy, modifier = Modifier.fillMaxWidth()) { Text("Translate AI emergency response") }
                    if (translatedResponse.isNotBlank()) {
                        Text("Translated emergency message · review before speaking", fontWeight = FontWeight.SemiBold)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) { Text(translatedResponse, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(translatedResponse)) }) { Text("Copy") }
                            TextButton(onClick = { shareResult(translatedResponse) }) { Text("Share outside app") }
                            TextButton(onClick = { onSendToMesh(translatedResponse) }) { Text("Send to Mesh group") }
                            TextButton(onClick = { responseText = translatedResponse; ttsLanguage = "${targetLanguage}-IN"; voice = TimbreCatalog.voicesFor(ttsLanguage).firstOrNull().orEmpty() }) { Text("Use for speech") }
                        }
                    }
                    if (!translationConfigured) Text("Translation requires a Google Cloud Translation API key in local.properties. See docs/MULTILINGUAL_WORKFLOW.md.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Evon · Indic language assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedTextField(value = evonPrompt, onValueChange = { evonPrompt = it.take(8_000) }, label = { Text("Ask Evon about this emergency message") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    if (transcript.isNotBlank()) TextButton(onClick = { evonPrompt = transcriptDraft }) { Text("Use Prisma transcript") }
                    Button(onClick = { onEvonAnalyze(transcriptDraft, latestVoiceMessageId) }, enabled = transcript.isNotBlank() && emergencyAnalysis.isBlank() && latestVoiceMessageId.isNotBlank() && onlineMode && internetAvailable && evonConfigured && !busy, modifier = Modifier.fillMaxWidth()) { Text("Extract structured emergency information") }
                    Button(onClick = { onEvonAsk(evonPrompt.trim()) }, enabled = evonPrompt.isNotBlank() && onlineMode && internetAvailable && evonConfigured && !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Ask Evon")
                    }
                    if (evonAnswer.isNotBlank()) {
                        Text("AI-generated suggested response · review before use", fontWeight = FontWeight.SemiBold)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                            Text(evonAnswer, modifier = Modifier.fillMaxWidth().padding(12.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(evonAnswer)) }) { Text("Copy") }
                            TextButton(onClick = { shareResult(evonAnswer) }) { Text("Share") }
                            TextButton(onClick = { responseText = evonAnswer }) { Text("Use for speech") }
                        }
                    }
                    if (emergencyAnalysis.isNotBlank()) {
                        OutlinedButton(onClick = { confirmShareSummary = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Campaign, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Share reviewed summary over local mesh")
                        }
                    }
                    if (!evonConfigured) Text("Evon needs a self-hosted HTTPS vLLM/SGLang endpoint. Configure evon.base.url in local.properties; no hosted endpoint is assumed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("AI output is advisory only. It does not dispatch responders or verify emergency facts.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Timbre · Text to speech", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Use this stage when someone cannot read the message easily or needs to hear a spoken emergency instruction. Timbre creates a saved voice file and requires its configured API key and internet. The phone's built-in speech option below can speak text without a Timbre key; offline use depends on installed Android voice data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = responseText, onValueChange = { responseText = it.take(4000) }, label = { Text("Emergency response or translated text") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { languageMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(TimbreCatalog.languages.firstOrNull { it.first == ttsLanguage }?.second ?: ttsLanguage) }
                            DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                                TimbreCatalog.languages.forEach { (code, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                        ttsLanguage = code
                                        voice = TimbreCatalog.voicesFor(code).firstOrNull().orEmpty()
                                        languageMenu = false
                                    })
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { voiceMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(voice.ifBlank { "Select voice" }) }
                            DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                                availableVoices.forEach { option ->
                                    DropdownMenuItem(text = { Text(option) }, onClick = { voice = option; voiceMenu = false })
                                }
                            }
                        }
                    }
                    Text("Voice options are restricted to the official Timbre v2.5 catalog for the selected language.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { speakOnPhone(responseText.trim(), ttsLanguage) }, enabled = responseText.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Speak on this phone") }
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(responseText)) }, enabled = responseText.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Copy response") }
                    }
                    Text(localTtsStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { onSendToMesh(responseText.trim()) }, enabled = responseText.isNotBlank(), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Send, null); Spacer(Modifier.width(6.dp)); Text("Send to Mesh group")
                        }
                        OutlinedButton(onClick = { shareResult(responseText) }, enabled = responseText.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Share outside app") }
                    }
                    Button(
                        onClick = {
                            val linkedId = latestVoiceMessageId.ifBlank {
                                responseMessageId.ifBlank { UUID.randomUUID().toString().also { responseMessageId = it } }
                            }
                            onSynthesize(responseText.trim(), ttsLanguage, voice, linkedId)
                        },
                        enabled = responseText.isNotBlank() && availableVoices.contains(voice) && onlineMode && configured && internetAvailable && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Generate spoken response") }
                    if (audioPath != null) {
                        Text("Saved audio · message ID: $audioMessageId", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { onPlayAudio(audioPath) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Play")
                            }
                            OutlinedButton(onClick = onPauseAudio, enabled = isAiAudioActive && audioPlaybackState == VoicePlaybackState.PLAYING, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Pause, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Pause")
                            }
                            OutlinedButton(onClick = onResumeAudio, enabled = isAiAudioActive && audioPlaybackState == VoicePlaybackState.PAUSED, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Resume")
                            }
                            OutlinedButton(onClick = { onReplayAudio(audioPath) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Replay, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Replay")
                            }
                        }
                        Text(
                            "Playback: ${if (isAiAudioActive) audioPlaybackState.name.lowercase().replace('_', ' ') else "not playing"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            val availability = when {
                !onlineMode -> "Offline Mode is selected. Switch to Online Mode to use hosted Gnani services."
                !configured -> "API key not configured. See docs/GNANI_CONFIGURATION.md."
                !internetAvailable -> "Internet unavailable. Gnani services are paused; BLE remains available."
                else -> "Gnani API key configured. Online services are reachable when the provider is available."
            }
            Text(availability, color = if (onlineMode && configured && internetAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (onlineMode && internetAvailable) {
                TextButton(onClick = onRetryQueued, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Resume queued AI tasks") }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Prisma and Timbre require internet. No mock transcript or generated audio is used.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}



private fun readAudioDurationMs(file: File?): Long {
    if (file == null || !file.isFile) return 0L
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
        0L
    } finally {
        runCatching { retriever.release() }
    }
}

private fun formatAudioDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "Unknown"
    val totalSeconds = durationMs / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

@Composable
private fun EmergencyCenter(
    category: String, onCategoryChange: (String) -> Unit, severity: Int, onSeverityChange: (Int) -> Unit,
    message: String, onMessageChange: (String) -> Unit, shareLocation: Boolean,
    onShareLocationChange: (Boolean) -> Unit, connectedPeers: List<MeshPeer>, destinationNodeId: String, onDestinationChange: (String) -> Unit, onContinue: () -> Unit
) {
    val categories = listOf("MEDICAL", "FIRE", "FLOOD", "ROAD_HAZARD", "SECURITY", "EARTHQUAKE", "OTHER")
    var categoryMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(34.dp))
                    Text("Emergency SOS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Send an emergency packet over the authenticated local BLE mesh. Internet, cloud login and AI services are not required.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        item {
            Text("Emergency category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(category.replace('_', ' '), Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null) }
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    categories.forEach { option -> DropdownMenuItem(text = { Text(option.replace('_', ' ')) }, onClick = { onCategoryChange(option); categoryMenu = false }) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Severity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("$severity / 5", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            Slider(value = severity.toFloat(), onValueChange = { onSeverityChange(it.toInt().coerceIn(1, 5)) }, valueRange = 1f..5f, steps = 3)
            Text("1 = Low • 3 = Moderate • 5 = Critical", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { OutlinedTextField(value = message, onValueChange = { onMessageChange(it.take(420)) }, modifier = Modifier.fillMaxWidth(), label = { Text("What happened? (optional)") }, placeholder = { Text("Describe the immediate emergency…") }, minLines = 3, maxLines = 5) }
        item {
            var targetMenu by remember { mutableStateOf(false) }
            Text("Who should receive this SOS?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedButton(onClick = { targetMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (destinationNodeId == "*") "All connected MeshLink peers · Group SOS" else connectedPeers.firstOrNull { it.nodeId == destinationNodeId }?.displayName ?: "Choose a connected peer", modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ExpandMore, null)
                }
                DropdownMenu(expanded = targetMenu, onDismissRequest = { targetMenu = false }) {
                    DropdownMenuItem(text = { Text("All connected peers · Group SOS") }, onClick = { onDestinationChange("*"); targetMenu = false })
                    connectedPeers.forEach { peer -> DropdownMenuItem(text = { Text("${peer.displayName} · Personal SOS") }, onClick = { onDestinationChange(peer.nodeId); targetMenu = false }) }
                }
            }
            Text(if (connectedPeers.isEmpty()) "No authenticated peer is connected yet. You can prepare an SOS, but delivery cannot happen until a mesh peer authenticates." else "${connectedPeers.size} authenticated peer(s) available. Choose one person or send to the group.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MyLocation, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Share GPS location", fontWeight = FontWeight.SemiBold)
                        Text("Optional. Included only when enabled and Android location permission is granted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = shareLocation, onCheckedChange = onShareLocationChange)
                }
            }
        }
        item {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), contentPadding = PaddingValues(vertical = 16.dp)) {
                Icon(Icons.Default.Warning, null); Spacer(Modifier.width(8.dp)); Text("Review emergency SOS", fontWeight = FontWeight.Bold)
            }
            Text("SOS is transmitted locally after confirmation. The local alarm and vibration do not depend on network services.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProfileHub(
    state: MeshUiState, events: List<EventEntity>, safety: List<MessageEntity>, cloudState: CloudDashboardState,
    appMode: AppMode, internetAvailable: Boolean,
    gnaniConfigured: Boolean, evonConfigured: Boolean, translationConfigured: Boolean, cloudConfigured: Boolean,
    onChangeMode: () -> Unit, onEditProfile: () -> Unit,
    onMeshToggle: (Boolean) -> Unit, onSafetyReview: (String, SafetyDecision) -> Unit, onCloudLogout: () -> Unit
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = section) {
            listOf("Profile", "Activity", "Safety").forEachIndexed { index, label -> Tab(selected = section == index, onClick = { section = index }, text = { Text(label) }) }
        }
        when (section) {
            0 -> LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Card {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(state.localName, 58.dp, state.localAvatarIndex); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(state.localName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onEditProfile) { Icon(Icons.Default.Edit, "Edit profile") }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Communication settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("Bluetooth mesh", fontWeight = FontWeight.SemiBold); Text(if (state.active) "Mesh service enabled" else "Mesh service disabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Switch(checked = state.active, onCheckedChange = onMeshToggle)
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("Application mode", fontWeight = FontWeight.SemiBold); Text("${appMode.label} • Internet ${if (internetAvailable) "available" else "unavailable"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                TextButton(onClick = onChangeMode) { Text("Change") }
                            }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Cloud account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(cloudState.user?.displayName ?: "Not signed in", fontWeight = FontWeight.SemiBold)
                            Text(cloudState.user?.let { "${it.email} • ${it.role}" } ?: "Cloud reporting requires an authenticated account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (cloudState.user != null) TextButton(onClick = onCloudLogout) { Text("Sign out") }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Privacy and sharing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Local messages and voice notes remain on this device unless shared. Cloud incident reports require authentication and consent. SOS location is optional.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("API service configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Prisma / Timbre: ${if (gnaniConfigured) "credentials configured" else "not configured"}", style = MaterialTheme.typography.bodySmall)
                            Text("Evon: ${if (evonConfigured) "endpoint configured" else "endpoint not configured"}", style = MaterialTheme.typography.bodySmall)
                            Text("Google Translation: ${if (translationConfigured) "key configured" else "not configured"}", style = MaterialTheme.typography.bodySmall)
                            Text("Cloud backend: ${if (cloudConfigured) "base URL configured" else "not configured"}", style = MaterialTheme.typography.bodySmall)
                            Text("Configuration is not a live health check. Speech language selection is available in Assistant and is limited to the configured service catalogs.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("About MeshLink", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Version 2.0 • Offline-first emergency communication prototype", style = MaterialTheme.typography.bodySmall)
                            Text("MeshLink supplements local coordination and does not replace official emergency services.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            1 -> Events(events)
            else -> Safety(safety, onSafetyReview)
        }
    }
}

@Composable
private fun Home(
    state: MeshUiState, appMode: AppMode, internetAvailable: Boolean,
    messages: List<MessageEntity>, pendingSync: Int, cloudIncidents: List<CloudIncident>, cloudConfigured: Boolean, cloudSignedIn: Boolean,
    gnaniConfigured: Boolean, evonConfigured: Boolean, translationConfigured: Boolean,
    onSosReview: (String, String) -> Unit, onOpenLocation: (Double, Double) -> Unit,
    onSos: () -> Unit, onAlert: () -> Unit, onLocation: () -> Unit, onVoice: () -> Unit,
    onOpenChats: () -> Unit, onOpenNearby: () -> Unit, onOpenAi: () -> Unit, onOpenReports: () -> Unit
) {
    val conversations = messages.asSequence().filter { it.receiverNodeId != "*" }
        .flatMap { sequenceOf(it.senderNodeId, it.receiverNodeId) }.filter { it.isNotBlank() }.distinct()
        .count { id -> state.peers.any { it.nodeId.equals(id, true) } }
    val emergencyMessages = messages.filter { it.type.uppercase(Locale.ROOT) in setOf("SOS", "ALERT") || it.priority >= 80 }
        .sortedByDescending { it.createdAt }.take(4)
    val onlineServicesReady = appMode == AppMode.ONLINE && internetAvailable
    val context = LocalContext.current
    val bluetoothRadioOff = state.bleStatus.contains("Bluetooth is OFF", ignoreCase = true) || state.bleStatus.contains("Bluetooth off", ignoreCase = true)
    val bleTransportReady = state.active && (
        state.bleStatus.contains("active", ignoreCase = true) ||
            state.bleStatus.contains("ready", ignoreCase = true)
        )
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
        item {
            val heroColors = if (appMode == AppMode.ONLINE) {
                listOf(Color(0xFF102B4D), Color(0xFF176B91), Color(0xFF087F8C))
            } else {
                listOf(Color(0xFF123B4A), Color(0xFF126C73), Color(0xFF168A83))
            }
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF102B4D))
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .background(Brush.linearGradient(heroColors))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (appMode == AppMode.ONLINE) "Connected when available. Ready when not." else "Your local emergency network", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(if (appMode == AppMode.ONLINE) "Online mode • AI and cloud services require validated internet." else "Offline mode • Nearby BLE communication works without internet.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.86f))
                            }
                            Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.16f)) {
                                Icon(if (appMode == AppMode.ONLINE) Icons.Default.CloudDone else Icons.Default.Bluetooth, null, Modifier.padding(12.dp).size(27.dp), tint = Color.White)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill("Internet", if (internetAvailable) "Connected" else "Disconnected", internetAvailable, Modifier.weight(1f))
                            StatusPill(
                                "BLE transport",
                                when {
                                    !state.active -> "Stopped"
                                    bleTransportReady -> "Active"
                                    state.bleStatus.contains("OFF", ignoreCase = true) -> "Bluetooth off"
                                    else -> "Starting / retrying"
                                },
                                bleTransportReady,
                                Modifier.weight(1f)
                            )
                        }
                        Text(
                            if (appMode == AppMode.OFFLINE) {
                                "Offline mode is selected. Internet status is shown separately; local SOS and BLE messaging do not depend on it."
                            } else {
                                "Online users: not provided by the configured backend. BLE peer counts below come from actual device discovery and authentication."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
        if (state.active && bluetoothRadioOff) item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BluetoothDisabled, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Turn on phone Bluetooth", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Text("MeshLink is running, but Android Bluetooth is switched off. Internet mode does not turn Bluetooth on. Enable Bluetooth on both phones to discover and connect nearby MeshLink devices.", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Enable Bluetooth") }
                }
            }
        }
        item {
            Text("Network overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMetric("Discovered", state.discoveredPeers.size.toString(), Icons.Default.BluetoothSearching, Modifier.weight(1f))
                DashboardMetric("Connected", state.peers.size.toString(), Icons.Default.VerifiedUser, Modifier.weight(1f))
                DashboardMetric("Active chats", conversations.toString(), Icons.Default.Chat, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMetric("Queued", state.queued.toString(), Icons.Default.Schedule, Modifier.weight(1f))
                DashboardMetric("Cloud sync", pendingSync.toString(), Icons.Default.CloudUpload, Modifier.weight(1f))
                DashboardMetric(
                    "AI setup",
                    when {
                        appMode != AppMode.ONLINE -> "Offline"
                        !internetAvailable -> "No internet"
                        gnaniConfigured && evonConfigured && translationConfigured -> "Ready*"
                        else -> "Partial"
                    },
                    Icons.Default.AutoAwesome,
                    Modifier.weight(1f)
                )
            }
            Text(
                "*Configured means credentials/settings exist, not that a live provider test passed. Prisma/Timbre: ${if (gnaniConfigured) "configured" else "not configured"} • Evon: ${if (evonConfigured) "configured" else "not configured"} • Translation: ${if (translationConfigured) "configured" else "not configured"}.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.lastRoute.isNotBlank()) {
                Text("Last observed relay: ${state.lastRoute}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().clickable(onClick = onSos), shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Emergency SOS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("Send directly over the local BLE mesh. No cloud connection required.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            Text("Quick actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction("Chats", Icons.Default.Chat, onOpenChats, Modifier.weight(1f))
                QuickAction("Nearby", Icons.Default.LocationOn, onOpenNearby, Modifier.weight(1f))
                QuickAction("Response", Icons.Default.HealthAndSafety, onOpenAi, Modifier.weight(1f))
                QuickAction("Reports", Icons.Default.Assignment, onOpenReports, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAlert, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Campaign, null); Spacer(Modifier.width(5.dp)); Text("Local alert") }
                OutlinedButton(onClick = onLocation, modifier = Modifier.weight(1f)) { Icon(Icons.Default.LocationOn, null); Spacer(Modifier.width(5.dp)); Text("Share location") }
                OutlinedButton(onClick = onVoice, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(5.dp)); Text("Disaster response") }
            }
        }
        item {
            val receivedSos = state.lastSos?.takeIf { !it.local }
            Text("Emergency alerts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (receivedSos == null) {
                EmptyState(Icons.Default.NotificationsActive, "No received SOS alert", "A valid SOS received over the authenticated mesh will appear here.")
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("SOS RECEIVED", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.error)
                            Text(SimpleDateFormat("dd MMM • HH:mm", Locale.getDefault()).format(Date(receivedSos.timestamp)), style = MaterialTheme.typography.labelSmall)
                        }
                        Text("From ${receivedSos.sender}", fontWeight = FontWeight.SemiBold)
                        Text(receivedSos.message, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Location: ${receivedSos.location}" +
                                (receivedSos.accuracyMeters?.let { " • accuracy ±${"%.0f".format(it)} m" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("Voice attachment: ${if (receivedSos.hasVoice) "included" else "none"} • Delivery: ${receivedSos.deliveryStatus} • Review: ${receivedSos.reviewStatus}", style = MaterialTheme.typography.labelSmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onSosReview(receivedSos.messageId, "REVIEW") }, modifier = Modifier.weight(1f)) { Text("Review") }
                            Button(onClick = { onSosReview(receivedSos.messageId, "PASS") }, modifier = Modifier.weight(1f)) { Text("Acknowledge") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onSosReview(receivedSos.messageId, "REJECT") }) { Text("Reject") }
                            val alertLatitude = receivedSos.latitude
                            val alertLongitude = receivedSos.longitude
                            if (alertLatitude != null && alertLongitude != null) {
                                TextButton(onClick = { onOpenLocation(alertLatitude, alertLongitude) }) {
                                    Icon(Icons.Default.Map, null); Spacer(Modifier.width(4.dp)); Text("Open location")
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Recent emergency messages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpenChats) { Text("Open chats") }
            }
            if (emergencyMessages.isEmpty()) EmptyState(Icons.Default.Shield, "No emergency messages", "Actual SOS and high-priority mesh messages will appear here.")
            else Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                emergencyMessages.forEach { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = if (message.type.equals("SOS", true)) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(message.type, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(message.payloadText.orEmpty().ifBlank { "${message.status} • ${message.senderName}" }, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Text("${message.senderName} • ${message.status}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Online incidents", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpenReports) { Text("View reports") }
            }
            when {
                onlineServicesReady && cloudConfigured && cloudSignedIn && cloudIncidents.isEmpty() -> Text("No incidents are currently available from the cloud service.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                onlineServicesReady && cloudConfigured && cloudSignedIn -> Text("${cloudIncidents.size} incident record(s) loaded from the authenticated cloud service.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                cloudIncidents.isNotEmpty() -> Text("${cloudIncidents.size} cached incident record(s). Sign in and refresh online to retrieve current reports.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("Incident data is not currently being fetched. No live incident count is available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, value: String, active: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable private fun Stat(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun Chat(
    messages: List<MessageEntity>, peers: List<MeshPeer>, discoveredPeers: List<MeshPeer>, selected: MeshPeer?, groupMode: Boolean,
    onBack: () -> Unit, onSelect: (MeshPeer) -> Unit, onSelectGroup: () -> Unit,
    text: String, onClear: () -> Unit, onSend: (MeshPeer, String) -> Unit, onBroadcast: (String) -> Unit, onTextChange: (String) -> Unit,
    onVoiceStart: () -> Unit, onVoiceStop: (MeshPeer?) -> Unit, recording: Boolean, setRecording: (Boolean) -> Unit,
    lastVoicePath: String?, onPlayVoice: (String) -> Unit, onLocation: () -> Unit,
    onRetryMessage: (String) -> Unit, onOpenSos: () -> Unit, playingVoicePath: String? = null
) {
    if (selected == null && !groupMode) {
        val latestGroup = messages.filter { it.receiverNodeId == "*" }.maxByOrNull { it.createdAt }
        Column(Modifier.fillMaxSize()) {
            Text("CONVERSATIONS", Modifier.padding(start = 18.dp, top = 14.dp, bottom = 8.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { onSelectGroup() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                ListItem(
                    headlineContent = { Text("Mesh Group", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(latestGroup?.payloadText ?: "Broadcast to authenticated MeshLink peers", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Avatar("MG", 48.dp) },
                    trailingContent = { latestGroup?.let { ChatTime(it.createdAt) } }
                )
            }
            Spacer(Modifier.height(6.dp))
            if (peers.isEmpty()) {
                EmptyState(Icons.Default.Chat, "No personal chats yet", "Authenticated MeshLink peers will appear here automatically.")
            } else {
                Text("PEERS", Modifier.padding(start = 18.dp, top = 8.dp, bottom = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(peers, key = { it.address }) { peer ->
                        val latest = messages.filter { it.senderNodeId.equals(peer.nodeId, true) || it.receiverNodeId.equals(peer.nodeId, true) }.maxByOrNull { it.createdAt }
                        ListItem(
                            headlineContent = { Text(peer.displayName, fontWeight = FontWeight.SemiBold) },
                            supportingContent = {
                                Text(
                                    latest?.let { if (it.type == "VOICE_CHUNK") "Voice message" else it.payloadText.orEmpty().ifBlank { it.status.lowercase().replace('_', ' ') } }
                                        ?: "${peer.nodeId.takeLast(8)} • ${signalText(peer.rssi)}",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = { Avatar(peer.displayName, 50.dp) },
                            trailingContent = {
                                Column(horizontalAlignment = Alignment.End) {
                                    latest?.let { ChatTime(it.createdAt) }
                                    Spacer(Modifier.height(3.dp))
                                    Text(signalText(peer.rssi), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            modifier = Modifier.clickable { onSelect(peer) }
                        )
                        HorizontalDivider(Modifier.padding(start = 82.dp))
                    }
                }
            }
        }
        return
    }

    if (groupMode) {
        val chatMessages = messages.filter { it.receiverNodeId == "*" && it.type != "SOS_REVIEW" }
        val listState = rememberLazyListState()
        LaunchedEffect(chatMessages.size) { if (chatMessages.isNotEmpty()) listState.animateScrollToItem(chatMessages.lastIndex) }
        val chatContext = LocalContext.current
        Column(Modifier.fillMaxSize()) {
            Surface(color = if (peers.isEmpty()) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (peers.isEmpty()) "No authenticated mesh connection" else "${peers.size} authenticated peer(s) connected", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            peers.isNotEmpty() -> "Group messages can be sent to connected peers and relayed by the mesh."
                            discoveredPeers.isNotEmpty() -> "${discoveredPeers.size} device(s) detected, but the secure connection is not complete yet. Keep both phones awake, Bluetooth on, and MeshLink enabled."
                            else -> "No MeshLink beacon detected. Turn on Bluetooth and MeshLink on both phones, keep them nearby, and grant Nearby Devices permissions."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (peers.isEmpty()) {
                        OutlinedButton(onClick = {
                            runCatching { chatContext.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open Bluetooth settings") }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 10.dp)) {
                if (chatMessages.isEmpty()) item { EmptyState(Icons.Default.Groups, "Mesh Group", "Messages are stored on this phone until at least one authenticated peer is available. A queued message is not delivered.") }
                items(chatMessages, key = { it.messageId }) { message -> MessageBubble(message, onPlayVoice, onRetryMessage, playingVoicePath) }
            }
            ChatComposer(text, onTextChange, onClear, onLocation, recording, setRecording, onVoiceStart, { onVoiceStop(null) }, null, onBroadcast, onEmergency = onOpenSos)
        }
        return
    }

    val peer = selected ?: return
    val chatMessages = messages.filter { it.senderNodeId.equals(peer.nodeId, true) || it.receiverNodeId.equals(peer.nodeId, true) }
    val listState = rememberLazyListState()
    LaunchedEffect(chatMessages.size) { if (chatMessages.isNotEmpty()) listState.animateScrollToItem(chatMessages.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 10.dp)) {
            if (chatMessages.isEmpty()) item { EmptyState(Icons.Default.ChatBubbleOutline, "Start the conversation", "Messages are stored locally and travel through authenticated BLE peers.") }
            items(chatMessages, key = { it.messageId }) { message -> MessageBubble(message, onPlayVoice, onRetryMessage, playingVoicePath) }
        }
        lastVoicePath?.let { path ->
            Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Latest voice recording", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { onPlayVoice(path) }) { Icon(Icons.Default.PlayArrow, "Play voice") }
                }
            }
        }
        ChatComposer(text, onTextChange, onClear, onLocation, recording, setRecording, onVoiceStart, { onVoiceStop(peer) }, peer, null, onSend = onSend, onEmergency = onOpenSos)
    }
}

@Composable
private fun ChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
    onLocation: () -> Unit,
    recording: Boolean,
    setRecording: (Boolean) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    peer: MeshPeer?,
    onBroadcast: ((String) -> Unit)? = null,
    onSend: ((MeshPeer, String) -> Unit)? = null,
    onEmergency: (() -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 7.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { if (!recording) { setRecording(true); onVoiceStart() } else { setRecording(false); onVoiceStop() } }) {
                Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, if (recording) "Stop recording" else "Voice message")
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (peer == null) "Message everyone" else "Message") },
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            )
            IconButton(onClick = onLocation) { Icon(Icons.Default.LocationOn, "Share location") }
            if (onEmergency != null) {
                IconButton(onClick = onEmergency) {
                    Icon(Icons.Default.Warning, "Open SOS", tint = MaterialTheme.colorScheme.error)
                }
            }
            FilledIconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        if (peer != null && onSend != null) onSend(peer, text)
                        else onBroadcast?.invoke(text)
                        onClear()
                    }
                },
                enabled = text.isNotBlank()
            ) {
                Icon(Icons.Default.Send, "Send message")
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    onPlayVoice: (String) -> Unit,
    onRetryMessage: (String) -> Unit,
    playingVoicePath: String? = null
) {
    val incoming = message.isIncoming
    val isVoice = message.type == "VOICE_CHUNK" && !message.payloadReference.isNullOrBlank()
    val isSos = message.type.equals("SOS", true) || message.priority >= 100
    val bubbleColor = if (isSos) MaterialTheme.colorScheme.errorContainer else if (incoming) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End) {
        Surface(
            color = bubbleColor,
            tonalElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(20.dp, 20.dp, if (incoming) 20.dp else 5.dp, if (incoming) 5.dp else 20.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                if (isSos) Text("EMERGENCY SOS", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.error)
                if (incoming) Text(message.senderName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (isVoice) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val path = message.payloadReference
                        val isPlaying = path != null && path == playingVoicePath
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                            IconButton(onClick = { path?.let(onPlayVoice) }) {
                                Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, if (isPlaying) "Stop voice" else "Play voice")
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Voice message", fontWeight = FontWeight.SemiBold)
                            Text(if (isPlaying) "Playing…" else "Tap to listen", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            color = if (isPlaying) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (isPlaying) "STOP" else "PLAY", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (isPlaying) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!incoming) Icon(Icons.Default.DoneAll, "Delivery acknowledgement", Modifier.size(13.dp), tint = if (message.delivered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(message.payloadText.orEmpty(), fontSize = 15.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!incoming) {
                            Spacer(Modifier.width(5.dp))
                            val tint = if (message.delivered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Icon(Icons.Default.DoneAll, "Delivery acknowledgement", Modifier.size(14.dp), tint = tint)
                            Text(if (message.delivered) "Delivered" else message.status.lowercase().replace('_', ' '), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val canRetry = !incoming && message.type in setOf("TEXT", "SOS", "ALERT", "LOCATION", "SOS_REVIEW") &&
                        message.status in setOf("QUEUED", "FORWARDED")
                    if (canRetry) {
                        TextButton(
                            onClick = { onRetryMessage(message.messageId) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry send")
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ChatTime(timestamp: Long) {
    Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MapTab(state: MeshUiState) {
    val unauthenticated = state.discoveredPeers.filterNot { discovered ->
        state.peers.any { it.nodeId.equals(discovered.nodeId, ignoreCase = true) }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Nearby devices", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Discovery and authenticated connections are shown separately. The local map uses only real GPS fixes shared by authenticated peers.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("Discovered", state.discoveredPeers.size.toString(), state.discoveredPeers.isNotEmpty(), Modifier.weight(1f))
                StatusPill("Authenticated", state.peers.size.toString(), state.peers.isNotEmpty(), Modifier.weight(1f))
            }
        }
        item { OfflineGeoMap(state) }
        item { TopologyCard(state) }
        item {
            Text("Nearby authenticated devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (state.peers.isEmpty()) EmptyState(Icons.Default.BluetoothSearching, "No nearby devices", "Only peers that complete the authenticated MeshLink handshake are listed here.")
        }
        items(state.peers, key = { "auth-${it.address}" }) { peer ->
            Card {
                ListItem(
                    headlineContent = { Text(peer.displayName, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Node ${peer.nodeId.takeLast(8)} • ${signalText(peer.rssi)} • connected • seen ${((System.currentTimeMillis() - peer.lastSeen).coerceAtLeast(0L) / 1000L)}s ago") },
                    leadingContent = { Avatar(peer.displayName, 42.dp) },
                    trailingContent = { Icon(Icons.Default.VerifiedUser, "Authenticated peer", tint = MaterialTheme.colorScheme.primary) }
                )
            }
        }
        if (unauthenticated.isNotEmpty()) {
            item {
                Text("Discovered · authentication pending", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("These devices broadcast a MeshLink discovery beacon but have not completed the authenticated handshake.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(unauthenticated, key = { "seen-${it.address}" }) { peer ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    ListItem(
                        headlineContent = { Text(peer.displayName.ifBlank { "MeshLink device" }, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text("Node ${peer.nodeId.takeLast(8)} • ${signalText(peer.rssi)} • discovered ${((System.currentTimeMillis() - peer.lastSeen).coerceAtLeast(0L) / 1000L)}s ago") },
                        leadingContent = { Icon(Icons.Default.BluetoothSearching, null, tint = MaterialTheme.colorScheme.secondary) },
                        trailingContent = { Text("Pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                }
            }
        }
        item { RealLocationRadar(state) }
    }
}

@Composable
private fun OfflineGeoMap(state: MeshUiState) {
    val localLat = state.localLatitude
    val localLon = state.localLongitude
    val visible = state.peerLocations.values.filter { loc ->
        state.peers.any { peer -> peer.nodeId.equals(loc.nodeId, ignoreCase = true) }
    }
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Real GPS points", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (localLat == null || localLon == null) {
                Text("No local GPS fix yet. Share Location to establish the real map center.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val primary = MaterialTheme.colorScheme.primary
                    val secondary = MaterialTheme.colorScheme.secondary
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        // Local grid only; it is a geographic plotting surface, not an online map tile.
                        for (i in 1..4) {
                            val x = size.width * i / 5f
                            val y = size.height * i / 5f
                            drawLine(primary.copy(alpha = 0.08f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                            drawLine(primary.copy(alpha = 0.08f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
                        }
                        drawCircle(primary, 9f, androidx.compose.ui.geometry.Offset(cx, cy))
                        visible.forEach { loc ->
                            val dx = ((loc.longitude - localLon) * 111320.0 * kotlin.math.cos(Math.toRadians(localLat))).toFloat()
                            val dy = ((loc.latitude - localLat) * 110540.0).toFloat()
                            val scale = 0.055f
                            val x = (cx + dx * scale).coerceIn(18f, size.width - 18f)
                            val y = (cy - dy * scale).coerceIn(18f, size.height - 18f)
                            drawLine(primary.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(cx, cy), androidx.compose.ui.geometry.Offset(x, y), 2f)
                            drawCircle(secondary, 8f, androidx.compose.ui.geometry.Offset(x, y))
                        }
                    }
                    Text("YOU", Modifier.align(Alignment.Center).offset(y = 18.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    if (visible.isEmpty()) {
                        Text("No authenticated peer GPS fixes shared yet", Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), fontSize = 11.sp)
                    } else {
                        Column(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                            visible.take(5).forEach { Text("• ${it.name}", fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopologyCard(state: MeshUiState) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Offline Mesh Topology", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Only observed authenticated BLE peers are shown. No fake nodes or distances.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                val topologyPrimary = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val visiblePeers = state.peers.take(6)
                    if (visiblePeers.isNotEmpty()) {
                        visiblePeers.forEachIndexed { i, _ ->
                            val angle = (Math.PI * 2.0 * i / visiblePeers.size) - Math.PI / 2.0
                            val px = cx + kotlin.math.cos(angle).toFloat() * (size.minDimension * 0.34f)
                            val py = cy + kotlin.math.sin(angle).toFloat() * (size.minDimension * 0.34f)
                            drawLine(topologyPrimary.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(cx, cy), androidx.compose.ui.geometry.Offset(px, py), strokeWidth = 3f)
                            drawCircle(topologyPrimary.copy(alpha = 0.18f), 24f, androidx.compose.ui.geometry.Offset(px, py))
                        }
                    }
                    drawCircle(topologyPrimary, 30f, androidx.compose.ui.geometry.Offset(cx, cy))
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(state.localName, 56.dp, state.localAvatarIndex)
                    Text("YOU", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                if (state.peers.isEmpty()) {
                    Text("Waiting for authenticated peers…", Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), fontSize = 12.sp)
                } else {
                    state.peers.take(6).forEachIndexed { index, peer ->
                        val count = state.peers.take(6).size
                        val angle = (Math.PI * 2.0 * index / count) - Math.PI / 2.0
                        val x = kotlin.math.cos(angle).toFloat() * 88.dp.value
                        val y = kotlin.math.sin(angle).toFloat() * 88.dp.value
                        Column(Modifier.align(Alignment.Center).offset(x.dp, y.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(peer.displayName, 42.dp)
                            Text(peer.displayName.take(10), fontSize = 9.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshTopology(state: MeshUiState) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Offline Mesh Topology", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Only observed authenticated BLE links are shown. No Internet map or fake nodes.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                val topologyPrimary = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val count = state.peers.size.coerceAtMost(6)
                    if (count > 0) {
                        for (i in 0 until count) {
                            val angle = (Math.PI * 2.0 * i / count) - Math.PI / 2.0
                            val px = cx + kotlin.math.cos(angle).toFloat() * (size.minDimension * 0.34f)
                            val py = cy + kotlin.math.sin(angle).toFloat() * (size.minDimension * 0.34f)
                            drawLine(topologyPrimary.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(cx, cy), androidx.compose.ui.geometry.Offset(px, py), strokeWidth = 3f)
                            drawCircle(topologyPrimary.copy(alpha = 0.18f), 24f, androidx.compose.ui.geometry.Offset(px, py))
                        }
                    }
                    drawCircle(topologyPrimary, 30f, androidx.compose.ui.geometry.Offset(cx, cy))
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(state.localName, 56.dp, state.localAvatarIndex)
                    Text("YOU", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                if (state.peers.isEmpty()) {
                    Text("Waiting for authenticated peers…", Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), fontSize = 12.sp)
                } else {
                    state.peers.take(6).forEachIndexed { index, peer ->
                        val angle = (Math.PI * 2.0 * index / state.peers.take(6).size) - Math.PI / 2.0
                        val radius = 88.dp
                        val x = kotlin.math.cos(angle).toFloat() * radius.value
                        val y = kotlin.math.sin(angle).toFloat() * radius.value
                        Column(Modifier.align(Alignment.Center).offset(x.dp, y.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(peer.displayName, 42.dp)
                            Text(peer.displayName.take(10), fontSize = 9.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        item {
            RealLocationRadar(state)
        }
        items(state.peers, key = { it.address }) { peer ->
            ListItem(
                headlineContent = { Text(peer.displayName, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("${peer.nodeId.takeLast(8)} • ${signalText(peer.rssi)}") },
                leadingContent = { Avatar(peer.displayName, 42.dp) },
                trailingContent = { Text("${peer.rssi} dBm", fontSize = 10.sp) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun RealLocationRadar(state: MeshUiState) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Real Location Radar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Only GPS coordinates actually shared by MeshLink devices are plotted. No estimated or fake distances.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            val lat0 = state.localLatitude
            val lon0 = state.localLongitude
            if (lat0 == null || lon0 == null) {
                Text("Your GPS location is not available yet. Tap Location to share a real fix.", fontSize = 12.sp)
            } else {
                Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val primary = MaterialTheme.colorScheme.primary
                    val secondary = MaterialTheme.colorScheme.secondary
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val radius = minOf(size.width, size.height) * 0.40f
                        for (ring in 1..3) drawCircle(primary.copy(alpha = 0.10f), radius * ring / 3f, androidx.compose.ui.geometry.Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                        drawCircle(primary, 10f, androidx.compose.ui.geometry.Offset(cx, cy))
                        state.peerLocations.values.filter { loc -> state.peers.any { peer -> peer.nodeId.equals(loc.nodeId, ignoreCase = true) } }.forEach { loc ->
                            val dx = ((loc.longitude - lon0) * 111320.0 * kotlin.math.cos(Math.toRadians(lat0))).toFloat()
                            val dy = ((loc.latitude - lat0) * 110540.0).toFloat()
                            val scale = 0.08f
                            val x = (cx + dx * scale).coerceIn(18f, size.width - 18f)
                            val y = (cy - dy * scale).coerceIn(18f, size.height - 18f)
                            drawCircle(secondary, 9f, androidx.compose.ui.geometry.Offset(x, y))
                        }
                    }
                    Text("YOU", Modifier.align(Alignment.Center).offset(y = 20.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    val visibleLocations = state.peerLocations.values.filter { loc -> state.peers.any { peer -> peer.nodeId.equals(loc.nodeId, ignoreCase = true) } }
                    if (visibleLocations.isEmpty()) Text("No connected peer GPS fixes shared yet", Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), fontSize = 11.sp)
                    else Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) { visibleLocations.take(4).forEach { Text("• ${it.name}: ${"%.5f".format(it.latitude)}, ${"%.5f".format(it.longitude)}", fontSize = 9.sp) } }
                }
            }
        }
    }
}

@Composable private fun Events(events: List<EventEntity>) {
    if (events.isEmpty()) EmptyState(Icons.Default.List, "No events yet", "Mesh, security, relay and SOS events will appear here.")
    else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(events, key = { it.eventId }) { event ->
            Card { ListItem(headlineContent = { Text(event.category, fontWeight = FontWeight.Bold) }, supportingContent = { Text("${event.details}\n${event.nodeId?.takeLast(8).orEmpty()} ${event.messageId?.takeLast(8).orEmpty()}") }, leadingContent = { Icon(Icons.Default.Info, null) }) }
        }
    }
}

@Composable
private fun Safety(items: List<MessageEntity>, onReview: (String, SafetyDecision) -> Unit) {
    val held = items.count { it.status == "HELD" }
    val blocked = items.count { it.status == "BLOCKED" }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 20.dp)
    ) {
        item {
            Text("Safety & Vulnerability", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(
                "Offline rule-based protection checks suspicious patterns, repetition, flooding and malformed traffic. It does not decide whether a real-world claim is true or false.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SafetyStat("OPEN", held, MaterialTheme.colorScheme.tertiaryContainer, Modifier.weight(1f))
                SafetyStat("BLOCKED", blocked, MaterialTheme.colorScheme.errorContainer, Modifier.weight(1f))
                SafetyStat("TOTAL", items.size, MaterialTheme.colorScheme.primaryContainer, Modifier.weight(1f))
            }
        }
        if (items.isEmpty()) {
            item { EmptyState(Icons.Default.Shield, "Nothing needs review", "Suspicious non-emergency traffic will appear here before it can propagate through the mesh.") }
        } else {
            items(items, key = { it.messageId }) { message ->
                val isBlocked = message.status == "BLOCKED"
                val isHeld = message.status == "HELD"
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isBlocked) MaterialTheme.colorScheme.errorContainer
                        else if (isHeld) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isBlocked) Icons.Default.Block else Icons.Default.Shield, null, tint = if (isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (isBlocked) "HIGH-RISK / BLOCKED" else if (isHeld) "SUSPICIOUS / REVIEW" else "PASSED", fontWeight = FontWeight.Bold)
                                Text("${message.type} • ${message.senderName}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            ChatTime(message.createdAt)
                        }
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
                            Text(message.payloadText.orEmpty(), Modifier.fillMaxWidth().padding(12.dp), maxLines = 6, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(message.lastError ?: "Local policy result", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (isHeld || isBlocked) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onReview(message.messageId, SafetyDecision.BLOCK) }) { Text("Block") }
                                TextButton(onClick = { onReview(message.messageId, SafetyDecision.HOLD) }) { Text("Keep Held") }
                                Button(onClick = { onReview(message.messageId, SafetyDecision.PASS) }) { Text("Pass & Release") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyStat(label: String, value: Int, container: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = container), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private val AvatarOptions = listOf("🟢", "🔵", "🟣", "🟠", "🌿", "⭐", "⚡", "🛡️")

@Composable private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp, avatarIndex: Int? = null, selected: Boolean = false) {
    val accents = listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
    val accent = accents[(name.hashCode() and Int.MAX_VALUE) % accents.size]
    val emoji = avatarIndex?.let { AvatarOptions.getOrNull(it) }
    Box(Modifier.size(size).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
        if (emoji != null) Text(emoji, fontSize = (size.value * 0.43f).sp)
        else Text(name.trim().firstOrNull()?.uppercase() ?: "M", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        if (selected) {
            Surface(Modifier.matchParentSize(), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), shape = CircleShape) {}
        }
    }
}

@Composable private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp)); Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun signalText(rssi: Int): String = when { rssi > -60 -> "Excellent signal"; rssi > -80 -> "Good signal"; else -> "Weak signal" }

private fun Modifier.sosHoldGesture(onTriggered: () -> Unit): Modifier = pointerInput(onTriggered) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val up = withTimeoutOrNull(2_100L) { waitForUpOrCancellation() }
        if (up != null && up.uptimeMillis - down.uptimeMillis >= 2_000L) {
            onTriggered()
        }
    }
}

