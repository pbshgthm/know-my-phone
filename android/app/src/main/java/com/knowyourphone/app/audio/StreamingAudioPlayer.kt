package com.knowyourphone.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
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
            onLevelChanged?.invoke(computePcmLevel(pcmData))
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

    /**
     * Compute RMS level from 16-bit little-endian PCM samples.
     * Returns a normalized float in 0..1 range.
     */
    private fun computePcmLevel(pcmData: ByteArray): Float {
        if (pcmData.size < 2) return 0f

        val sampleCount = pcmData.size / 2
        var sumSquares = 0.0

        for (i in 0 until sampleCount) {
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low // 16-bit signed little-endian
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }

        val rms = sqrt(sumSquares / sampleCount)
        // Scale for visual range (speech RMS is typically 0.02-0.15)
        return (rms * 3.0).coerceIn(0.0, 1.0).toFloat()
    }
}
