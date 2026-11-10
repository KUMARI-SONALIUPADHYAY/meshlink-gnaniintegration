package com.meshlink.app.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import java.io.File

enum class VoicePlaybackState { IDLE, PREPARING, PLAYING, PAUSED, ERROR }

/** Plays a specific completed voice asset and supports pause, resume and replay. */
class VoicePlayer {
    private var player: MediaPlayer? = null
    private var currentPath: String? = null
    var onPlaybackChanged: ((String?) -> Unit)? = null
    var onPlaybackStateChanged: ((VoicePlaybackState) -> Unit)? = null

    @Synchronized
    fun play(context: Context, path: String) {
        stop()
        val file = File(path)
        if (!file.isFile || file.length() == 0L) {
            Log.e(TAG, "Voice asset missing or empty")
            onPlaybackStateChanged?.invoke(VoicePlaybackState.ERROR)
            return
        }
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setOnPreparedListener { prepared ->
                    if (player === prepared) {
                        prepared.setVolume(1f, 1f)
                        prepared.start()
                        onPlaybackChanged?.invoke(file.absolutePath)
                        onPlaybackStateChanged?.invoke(VoicePlaybackState.PLAYING)
                    }
                }
                setOnCompletionListener { finished -> releaseIfCurrent(finished) }
                setOnErrorListener { failed, what, extra ->
                    Log.e(TAG, "Playback error what=$what extra=$extra")
                    releaseIfCurrent(failed, VoicePlaybackState.ERROR)
                    true
                }
            }
            player = mp
            currentPath = file.absolutePath
            onPlaybackStateChanged?.invoke(VoicePlaybackState.PREPARING)
            mp.setDataSource(context, Uri.fromFile(file))
            mp.prepareAsync()
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to prepare voice playback", t)
            player?.runCatching { release() }
            player = null
            currentPath = null
            onPlaybackChanged?.invoke(null)
            onPlaybackStateChanged?.invoke(VoicePlaybackState.ERROR)
        }
    }

    @Synchronized
    fun pause() {
        val active = player ?: return
        if (active.isPlaying) {
            runCatching { active.pause() }
            onPlaybackStateChanged?.invoke(VoicePlaybackState.PAUSED)
        }
    }

    @Synchronized
    fun resume() {
        val active = player ?: return
        if (!active.isPlaying) {
            runCatching {
                active.start()
                onPlaybackStateChanged?.invoke(VoicePlaybackState.PLAYING)
            }.onFailure { Log.e(TAG, "Unable to resume voice playback", it) }
        }
    }

    @Synchronized
    fun replay(context: Context, path: String) = play(context, path)

    @Synchronized
    fun stop() {
        val old = player
        player = null
        currentPath = null
        try { old?.stop() } catch (_: Throwable) {}
        try { old?.release() } catch (_: Throwable) {}
        onPlaybackChanged?.invoke(null)
        onPlaybackStateChanged?.invoke(VoicePlaybackState.IDLE)
    }

    @Synchronized
    private fun releaseIfCurrent(mp: MediaPlayer, state: VoicePlaybackState = VoicePlaybackState.IDLE) {
        if (player === mp) {
            player = null
            currentPath = null
            onPlaybackChanged?.invoke(null)
            onPlaybackStateChanged?.invoke(state)
        }
        runCatching { mp.release() }
    }

    companion object { private const val TAG = "MeshLinkVoice" }
}
