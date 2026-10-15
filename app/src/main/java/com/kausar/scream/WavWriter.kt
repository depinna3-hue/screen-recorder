package com.kausar.scream

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Tulis header WAV PCM 16-bit mono/stereo. */
object WavWriter {
    fun writeHeader(out: FileOutputStream, sampleRate: Int, channels: Int) {
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("RIFF".toByteArray())
        hdr.putInt(0) // placeholder size
        hdr.put("WAVE".toByteArray())
        hdr.put("fmt ".toByteArray())
        hdr.putInt(16)
        hdr.putShort(1) // PCM
        hdr.putShort(channels.toShort())
        hdr.putInt(sampleRate)
        hdr.putInt(sampleRate * channels * 2)
        hdr.putShort((channels * 2).toShort())
        hdr.putShort(16)
        hdr.put("data".toByteArray())
        hdr.putInt(0) // placeholder data size
        out.write(hdr.array())
    }

    fun finalize(file: File) {
        val total = file.length()
        val dataSize = (total - 44).toInt().coerceAtLeast(0)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intLE(dataSize + 36))
            raf.seek(40)
            raf.write(intLE(dataSize))
        }
    }

    private fun intLE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}