package com.knowyourphone.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Streams raw PCM audio (24kHz, 16-bit mono) via AudioTrack in MODE_STREAM.
 * Chunks are written from an IO thread; completion is detected by polling playback position.
 */
class StreamingAudioPlayer {
    companion object {
        private const val TAG = "StreamingAudioPlayer"
        private const val SAMPLE_RATE = 24000
    }

    @Volatile
    private var audioTrack: AudioTrack? = null
    private var visualizer: Visualizer? = null
    @Volatile
    private var totalFramesWritten = 0
    @Volatile
    var onLevelChanged: ((Float) -> Unit)? = null
    var onCompletion: (() -> Unit)? = null

    fun start() {
        stop()
        totalFramesWritten = 0

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        setupVisualizer(audioTrack!!.audioSessionId)
        audioTrack!!.play()
        Log.d(TAG, "AudioTrack started (24kHz PCM stream)")
    }

    /**
     * Write a PCM chunk. Call from IO thread — this may block if the buffer is full.
     */
    fun writeChunk(pcmData: ByteArray) {
        val track = audioTrack ?: return
        val written = track.write(pcmData, 0, pcmData.size)
        if (written > 0) {
            // 16-bit mono: each frame is 2 bytes
            totalFramesWritten += written / 2
        } else if (written < 0) {
            Log.e(TAG, "AudioTrack.write error: $written")
        }
    }

    /**
     * Signal that all data has been written. Polls for playback completion.
     * Call from a coroutine on IO dispatcher.
     */
    fun finish() {
        val track = audioTrack ?: run {
            onLevelChanged?.invoke(0f)
            onCompletion?.invoke()
            return
        }

        // Poll until playback catches up to total written frames
        Thread {
            try {
                while (true) {
                    val head = track.playbackHeadPosition
                    if (head >= totalFramesWritten || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        break
                    }
                    Thread.sleep(50)
                }
            } catch (_: InterruptedException) {
                // Interrupted — clean up
            }
            onLevelChanged?.invoke(0f)
            onCompletion?.invoke()
        }.start()
    }

    fun stop() {
        onCompletion = null
        onLevelChanged?.invoke(0f)
        onLevelChanged = null
        releaseVisualizer()
        try {
            audioTrack?.let {
                it.pause()
                it.flush()
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
        }
        audioTrack = null
        totalFramesWritten = 0
    }

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
                        onLevelChanged?.invoke(computeWaveformLevel(waveform))
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer,
                        fft: ByteArray,
                        samplingRate: Int
                    ) {
                        // Not used
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
        try { visualizer?.enabled = false } catch (_: Exception) {}
        try { visualizer?.release() } catch (_: Exception) {}
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
