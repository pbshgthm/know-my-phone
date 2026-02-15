package com.knowmyphone.app.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

/**
 * Plays a one-shot audio prompt from raw resources (e.g. share_screen.mp3).
 * Only one prompt plays at a time — calling play() cancels any in-flight playback.
 */
class PromptAudioPlayer(private val context: Context) {
    companion object {
        private const val TAG = "PromptAudioPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null

    fun play(rawResId: Int) {
        stop()
        try {
            mediaPlayer = MediaPlayer.create(context, rawResId)?.apply {
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play prompt audio: ${e.message}")
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}
