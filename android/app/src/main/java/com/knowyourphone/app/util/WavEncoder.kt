package com.knowyourphone.app.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {

    /**
     * Wraps raw PCM data in a 44-byte WAV header.
     * Assumes 16kHz, mono, 16-bit PCM.
     */
    fun encode(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize

        val buffer = ByteBuffer.allocate(44 + dataSize).apply {
            order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            put('R'.code.toByte())
            put('I'.code.toByte())
            put('F'.code.toByte())
            put('F'.code.toByte())
            putInt(fileSize)
            put('W'.code.toByte())
            put('A'.code.toByte())
            put('V'.code.toByte())
            put('E'.code.toByte())

            // fmt sub-chunk
            put('f'.code.toByte())
            put('m'.code.toByte())
            put('t'.code.toByte())
            put(' '.code.toByte())
            putInt(16) // sub-chunk size
            putShort(1) // PCM format
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())

            // data sub-chunk
            put('d'.code.toByte())
            put('a'.code.toByte())
            put('t'.code.toByte())
            put('a'.code.toByte())
            putInt(dataSize)
            put(pcmData)
        }

        return buffer.array()
    }
}
