package com.knowmyphone.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

class AudioRecorder {
    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    @Volatile
    private var isRecording = false
    private var pcmBuffer = ByteArrayOutputStream()
    @Volatile
    private var levelListener: ((Float) -> Unit)? = null

    val bufferSize: Int
        get() = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(2048)

    @SuppressLint("MissingPermission")
    fun startRecording(scope: CoroutineScope, onLevelChanged: ((Float) -> Unit)? = null): Boolean {
        if (isRecording) return true

        levelListener = onLevelChanged
        pcmBuffer.reset()
        val minBuf = bufferSize

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBuf * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        isRecording = true
        audioRecord?.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(minBuf)
            while (isRecording && isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    synchronized(pcmBuffer) {
                        pcmBuffer.write(buffer, 0, read)
                    }
                    levelListener?.invoke(computeNormalizedLevel(buffer, read))
                }
            }
        }

        Log.d(TAG, "Recording started")
        return true
    }

    fun stopRecording(): ByteArray {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val data = synchronized(pcmBuffer) {
            pcmBuffer.toByteArray()
        }
        levelListener?.invoke(0f)
        levelListener = null
        Log.d(TAG, "Recording stopped, PCM size: ${data.size} bytes")
        return data
    }

    fun cancelRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        pcmBuffer.reset()
        levelListener?.invoke(0f)
        levelListener = null
        Log.d(TAG, "Recording cancelled")
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    private fun computeNormalizedLevel(buffer: ByteArray, bytesRead: Int): Float {
        if (bytesRead < 2) return 0f

        var sumSquares = 0.0
        var peak = 0.0
        var sampleCount = 0
        var i = 0
        while (i + 1 < bytesRead) {
            val sample = (((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()).toInt()
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            peak = maxOf(peak, abs(normalized))
            sampleCount++
            i += 2
        }

        if (sampleCount == 0) return 0f
        val rms = sqrt(sumSquares / sampleCount)
        val blended = rms * 0.70 + peak * 0.30
        return (blended * 1.9).coerceIn(0.0, 1.0).toFloat()
    }
}
