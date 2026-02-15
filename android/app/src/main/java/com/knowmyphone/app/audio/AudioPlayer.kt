package com.knowmyphone.app.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

class AudioPlayer(private val context: Context) {
    companion object {
        private const val TAG = "AudioPlayer"
    }

    @Volatile
    private var mediaPlayer: MediaPlayer? = null
    private var onCompletionCallback: (() -> Unit)? = null
    @Volatile
    private var onLevelCallback: ((Float) -> Unit)? = null
    private var visualizer: Visualizer? = null

    fun playMp3Bytes(
        mp3Data: ByteArray,
        onLevelChanged: ((Float) -> Unit)? = null,
        onCompletion: () -> Unit
    ) {
        stop()
        onCompletionCallback = onCompletion
        onLevelCallback = onLevelChanged

        try {
            val cacheFile = File(context.cacheDir, "tts_response.mp3")
            FileOutputStream(cacheFile).use { fos ->
                fos.write(mp3Data)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(cacheFile.absolutePath)
                setOnCompletionListener {
                    Log.d(TAG, "Playback complete")
                    onLevelCallback?.invoke(0f)
                    onCompletionCallback?.invoke()
                    releaseVisualizer()
                    release()
                    mediaPlayer = null
                    onCompletionCallback = null
                    onLevelCallback = null
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Playback error: what=$what extra=$extra")
                    onLevelCallback?.invoke(0f)
                    onCompletionCallback?.invoke()
                    releaseVisualizer()
                    release()
                    mediaPlayer = null
                    onCompletionCallback = null
                    onLevelCallback = null
                    true
                }
                prepare()
                setupVisualizer(audioSessionId)
                start()
            }
            Log.d(TAG, "Playing MP3: ${mp3Data.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio: ${e.message}")
            onLevelCallback?.invoke(0f)
            releaseVisualizer()
            // Release the MediaPlayer if prepare() or start() failed
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
            val completion = onCompletionCallback
            onCompletionCallback = null
            onLevelCallback = null
            completion?.invoke()
        }
    }

    fun stop() {
        onCompletionCallback = null
        onLevelCallback?.invoke(0f)
        onLevelCallback = null
        releaseVisualizer()
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

    private fun setupVisualizer(audioSessionId: Int) {
        if (audioSessionId == 0) return
        releaseVisualizer()

        try {
            visualizer = Visualizer(audioSessionId).apply {
                enabled = false
                val captureRange = Visualizer.getCaptureSizeRange()
                captureSize = captureRange[1]
                val captureRate = Visualizer.getMaxCaptureRate()

                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer,
                        waveform: ByteArray,
                        samplingRate: Int
                    ) {
                        onLevelCallback?.invoke(computeWaveformLevel(waveform))
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer,
                        fft: ByteArray,
                        samplingRate: Int
                    ) {
                        // Not used; waveform is enough for level visualization.
                    }
                }, captureRate, true, false)

                enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Visualizer unavailable: ${e.message}")
            releaseVisualizer()
        }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
        } catch (_: Exception) {}

        try {
            visualizer?.release()
        } catch (_: Exception) {}

        visualizer = null
    }

    private fun computeWaveformLevel(waveform: ByteArray): Float {
        if (waveform.isEmpty()) return 0f

        var sumSquares = 0.0
        var peak = 0.0
        for (sample in waveform) {
            val centered = ((sample.toInt() and 0xFF) - 128) / 128.0
            sumSquares += centered * centered
            peak = maxOf(peak, abs(centered))
        }

        val rms = sqrt(sumSquares / waveform.size)
        val blended = rms * 0.65 + peak * 0.35
        return (blended * 1.8).coerceIn(0.0, 1.0).toFloat()
    }
}
