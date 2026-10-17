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
        hdr.put("RIFF".toByteArray(Charsets.US_ASCII))
        hdr.putInt(0) // placeholder size
        hdr.put("WAVE".toByteArray(Charsets.US_ASCII))
        hdr.put("fmt ".toByteArray(Charsets.US_ASCII))
        hdr.putInt(16)
        hdr.putShort(1) // PCM
        hdr.putShort(channels.toShort())
        hdr.putInt(sampleRate)
        hdr.putInt(sampleRate * channels * 2)
        hdr.putShort((channels * 2).toShort())
        hdr.putShort(16)
        hdr.put("data".toByteArray(Charsets.US_ASCII))
        hdr.putInt(0) // placeholder data size
        out.write(hdr.array())
    }

    /** Isi ukuran RIFF/data setelah rekaman selesai. Aman untuk file > 2 GB dan file yang terlalu pendek. */
    fun finalize(file: File) {
        val total = file.length()
        if (total < 44) return
        val dataSize = (total - 44).coerceAtMost(0xFFFFFFFFL - 36L)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intLE((dataSize + 36L).toInt()))
            raf.seek(40)
            raf.write(intLE(dataSize.toInt()))
        }
    }

    private fun intLE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
