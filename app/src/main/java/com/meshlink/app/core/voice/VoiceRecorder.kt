package com.meshlink.app.core.voice

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import java.io.File

class VoiceRecorder(private val context: Context) {
    private var startedAt = 0L
    var lastDurationMs: Long = 0L
        private set
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    fun start(): File? {
        if (recorder != null) return file
        val voiceDirectory = File(context.filesDir, "voice_messages").apply { mkdirs() }
        val f = File(voiceDirectory, "voice_${System.currentTimeMillis()}.m4a")
        return try {
            val r = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(8000)
                setMaxDuration(10_000)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
            recorder = r
            file = f
            startedAt = System.currentTimeMillis()
            lastDurationMs = 0L
            f
        } catch (t: Throwable) {
            Log.e("MeshLinkVoice", "Unable to start microphone recording", t)
            runCatching { f.delete() }
            recorder?.runCatching { release() }
            recorder = null
            file = null
            null
        }
    }

    fun stop(): File? {
        val f = file
        if (recorder == null) return null
        lastDurationMs = (System.currentTimeMillis() - startedAt).coerceIn(0, 15_000)
        try { recorder?.stop() } catch (t: Throwable) { Log.w("MeshLinkVoice", "Recorder stop failed", t) }
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        file = null
        return f?.takeIf { it.exists() && it.length() > 0L }
    }
}
