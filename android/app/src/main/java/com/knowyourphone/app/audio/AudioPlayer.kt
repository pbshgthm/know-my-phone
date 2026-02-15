package com.knowyourphone.app.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class AudioPlayer(private val context: Context) {
    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var onCompletionCallback: (() -> Unit)? = null

    fun playMp3Bytes(mp3Data: ByteArray, onCompletion: () -> Unit) {
        stop()
        onCompletionCallback = onCompletion

        try {
            val cacheFile = File(context.cacheDir, "tts_response.mp3")
            FileOutputStream(cacheFile).use { fos ->
                fos.write(mp3Data)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(cacheFile.absolutePath)
                setOnCompletionListener {
                    Log.d(TAG, "Playback complete")
                    onCompletionCallback?.invoke()
                    release()
                    mediaPlayer = null
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Playback error: what=$what extra=$extra")
                    onCompletionCallback?.invoke()
                    release()
                    mediaPlayer = null
                    true
                }
                prepare()
                start()
            }
            Log.d(TAG, "Playing MP3: ${mp3Data.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio: ${e.message}")
            // Release the MediaPlayer if prepare() or start() failed
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
            onCompletionCallback?.invoke()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping player: ${e.message}")
        }
        mediaPlayer = null
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true
}
