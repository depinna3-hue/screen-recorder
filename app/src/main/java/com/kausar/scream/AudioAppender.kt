package com.kausar.scream

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Menyambung rekaman baru (segmen) ke file rekaman lama — fitur "Lanjutkan rekaman".
 *
 * - WAV : data PCM segmen ditambahkan di akhir file lama, header diperbarui terakhir.
 *         Kalau gagal di tengah jalan, file lama tetap bisa diputar.
 * - M4A : AAC digabung tanpa encode ulang (MediaExtractor + MediaMuxer) lalu menimpa
 *         file lama. File lama dicadangkan dulu dan dikembalikan bila penimpaan gagal.
 */
object AudioAppender {

    private const val TAG = "AudioAppender"
    private const val AAC_MIME = "audio/mp4a-latm"

    /** Info format file lama; dipakai agar rekaman baru dibuat dengan format yang sama. */
    data class Info(val isWav: Boolean, val sampleRate: Int, val channels: Int)

    /**
     * uri = file yang diperbarui (atau file baru bila penyambungan gagal).
     * note kosong = sukses penuh; selain itu berisi penjelasan untuk pengguna.
     */
    data class Result(val uri: Uri?, val displayPath: String, val merged: Boolean, val note: String)

    private class WavInfo(val sampleRate: Int, val channels: Int, val dataOffset: Long)

    // ------------------------------------------------------------------ PROBE

    /** Periksa file lama. Melempar Exception berisi alasan bila tidak bisa dilanjutkan. */
    fun probe(ctx: Context, uri: Uri, ext: String): Info {
        return when (ext.lowercase()) {
            "wav" -> {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("File tidak bisa dibuka")
                val w = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { readWavInfo(it.channel) }
                Info(true, w.sampleRate, w.channels)
            }
            "m4a" -> {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(ctx, uri, null)
                    val t = findAudioTrack(ex)
                    if (t < 0) throw IllegalStateException("Track audio tidak ditemukan")
                    val f = ex.getTrackFormat(t)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime != AAC_MIME) throw IllegalStateException("Hanya AAC yang bisa dilanjutkan")
                    Info(
                        false,
                        f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    )
                } finally {
                    try { ex.release() } catch (_: Exception) {}
                }
            }
            else -> throw IllegalStateException("Format .$ext belum didukung")
        }
    }

    // ----------------------------------------------------------------- APPEND

    /**
     * Sambungkan [segment] (file sementara di cache) ke [target].
     * Melempar Exception HANYA bila segmen itu sendiri rusak/kosong (tidak ada yang perlu disimpan).
     * Bila penggabungan gagal tetapi segmen sehat, segmen disimpan sebagai rekaman terpisah.
     */
    fun append(ctx: Context, target: Uri, segment: File): Result {
        val isWav = segment.extension.equals("wav", true)
        val targetName = RecordingStore.nameOf(ctx, target) ?: "rekaman"

        if (!segmentValid(segment, isWav)) {
            try { segment.delete() } catch (_: Exception) {}
            throw IllegalStateException("Rekaman baru terlalu pendek / kosong")
        }

        try {
            if (isWav) appendWav(ctx, target, segment) else appendAac(ctx, target, segment)
            try { segment.delete() } catch (_: Exception) {}
            PublicStorage.rescan(ctx, target)
            return Result(target, "Musik/KausarRecorder/$targetName", true, "")
        } catch (e: Exception) {
            Log.e(TAG, "gabung gagal", e)
            // Simpan segmen terpisah supaya rekaman baru tidak hilang
            val base = targetName.substringBeforeLast('.')
            val renamed = File(segment.parentFile, "${base}_lanjutan.${segment.extension}")
            val toPublish = if (segment.renameTo(renamed)) renamed else segment
            val mime = if (isWav) "audio/wav" else "audio/mp4"
            val pub = PublicStorage.publishFileEx(ctx, toPublish, false, mime)
            return Result(
                pub.uri, pub.displayPath, false,
                "Gagal menggabungkan (${e.message}). Rekaman baru disimpan sebagai file terpisah."
            )
        }
    }

    private fun segmentValid(f: File, isWav: Boolean): Boolean {
        if (!f.exists()) return false
        if (isWav) return f.length() > 44 + 1024        // minimal ~ puluhan milidetik audio
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.absolutePath)
            val t = findAudioTrack(ex)
            if (t < 0) false else {
                ex.selectTrack(t)
                ex.sampleTime >= 0
            }
        } catch (_: Exception) {
            false
        } finally {
            try { ex.release() } catch (_: Exception) {}
        }
    }

    // -------------------------------------------------------------------- WAV

    private fun appendWav(ctx: Context, target: Uri, segment: File) {
        // 1) baca header file lama
        val rd = ctx.contentResolver.openFileDescriptor(target, "r")
            ?: throw IllegalStateException("File lama tidak bisa dibuka")
        val info = ParcelFileDescriptor.AutoCloseInputStream(rd).use { readWavInfo(it.channel) }

        // 2) buka untuk ditulis, tambahkan data PCM di akhir
        val wr = ctx.contentResolver.openFileDescriptor(target, "rw")
            ?: throw IllegalStateException("File lama tidak bisa ditulis")
        ParcelFileDescriptor.AutoCloseOutputStream(wr).use { os ->
            val ch = os.channel
            ch.position(ch.size())

            FileInputStream(segment).use { ins ->
                // lewati header 44 byte milik segmen (dibuat WavWriter)
                skipFully(ins, 44)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = fill(ins, buf)
                    if (n <= 0) break
                    val even = n and 1.inv()            // buang byte sisa (sampel 16-bit = 2 byte)
                    if (even <= 0) break
                    if (info.channels == 2) {
                        // segmen mono → stereo: gandakan tiap sampel
                        val outBuf = ByteArray(even * 2)
                        var s = 0
                        var d = 0
                        while (s < even) {
                            outBuf[d] = buf[s]
                            outBuf[d + 1] = buf[s + 1]
                            outBuf[d + 2] = buf[s]
                            outBuf[d + 3] = buf[s + 1]
                            s += 2
                            d += 4
                        }
                        writeFully(ch, ByteBuffer.wrap(outBuf, 0, outBuf.size))
                    } else {
                        writeFully(ch, ByteBuffer.wrap(buf, 0, even))
                    }
                }
            }

            // 3) perbarui header TERAKHIR (data dianggap chunk paling akhir, seperti buatan WavWriter)
            val total = ch.size()
            writeIntLE(ch, 4, (total - 8).coerceIn(0L, 0xFFFFFFFFL))
            writeIntLE(ch, info.dataOffset - 4, (total - info.dataOffset).coerceIn(0L, 0xFFFFFFFFL))
        }
    }

    private fun readWavInfo(ch: FileChannel): WavInfo {
        val size = ch.size()
        val head = readAt(ch, 0, 12) ?: throw IllegalStateException("Header WAV tidak valid")
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(head, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) throw IllegalStateException("Bukan file WAV")

        var pos = 12L
        var rate = 0
        var chs = 0
        var bits = 0
        var tag = 0
        while (pos + 8 <= size) {
            val h = readAt(ch, pos, 8) ?: break
            val id = String(h, 0, 4, Charsets.US_ASCII)
            val sz = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val body = pos + 8
            if (id == "fmt ") {
                val f = readAt(ch, body, 16) ?: throw IllegalStateException("Chunk fmt rusak")
                val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                tag = bb.short.toInt() and 0xFFFF
                chs = bb.short.toInt()
                rate = bb.int
                bb.int      // byte rate
                bb.short    // block align
                bits = bb.short.toInt()
            } else if (id == "data") {
                if (tag != 1 || bits != 16) throw IllegalStateException("Hanya WAV PCM 16-bit yang didukung")
                if (chs !in 1..2 || rate <= 0) throw IllegalStateException("Jumlah kanal/sample rate tidak didukung")
                return WavInfo(rate, chs, body)
            }
            pos = body + sz + (sz and 1L)
        }
        throw IllegalStateException("Chunk data WAV tidak ditemukan")
    }

    private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteArray? {
        val bb = ByteBuffer.allocate(len)
        var p = pos
        while (bb.hasRemaining()) {
            val n = ch.read(bb, p)
            if (n <= 0) return null
            p += n
        }
        return bb.array()
    }

    private fun writeIntLE(ch: FileChannel, pos: Long, v: Long) {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(v.toInt())
        bb.flip()
        var p = pos
        while (bb.hasRemaining()) p += ch.write(bb, p)
    }

    private fun writeFully(ch: FileChannel, bb: ByteBuffer) {
        while (bb.hasRemaining()) ch.write(bb)
    }

    /** Isi buffer sampai penuh atau file habis. */
    private fun fill(ins: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = ins.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun skipFully(ins: InputStream, count: Int) {
        var left = count
        val tmp = ByteArray(count)
        while (left > 0) {
            val n = ins.read(tmp, 0, left)
            if (n < 0) throw IllegalStateException("Segmen terlalu pendek")
            left -= n
        }
    }

    // -------------------------------------------------------------------- AAC

    private fun appendAac(ctx: Context, target: Uri, segment: File) {
        val ts = System.currentTimeMillis()
        val merged = File(ctx.cacheDir, "merged_$ts.m4a")
        val backup = File(ctx.cacheDir, "backup_$ts.m4a")
        try {
            mergeAac(ctx, target, segment, merged)
            if (!merged.exists() || merged.length() <= 0) throw IllegalStateException("Hasil gabungan kosong")

            // cadangkan file lama, lalu timpa isinya dengan hasil gabungan
            ctx.contentResolver.openInputStream(target)?.use { ins ->
                FileOutputStream(backup).use { o -> ins.copyTo(o) }
            } ?: throw IllegalStateException("File lama tidak bisa dibaca")

            try {
                val os = ctx.contentResolver.openOutputStream(target, "wt")
                    ?: throw IllegalStateException("File lama tidak bisa ditulis")
                os.use { o -> FileInputStream(merged).use { it.copyTo(o) } }
            } catch (e: Exception) {
                // kembalikan isi lama
                try {
                    ctx.contentResolver.openOutputStream(target, "wt")?.use { o ->
                        FileInputStream(backup).use { it.copyTo(o) }
                    }
                } catch (_: Exception) {}
                throw e
            }
        } finally {
            try { merged.delete() } catch (_: Exception) {}
            try { backup.delete() } catch (_: Exception) {}
        }
    }

    private fun mergeAac(ctx: Context, target: Uri, segment: File, out: File) {
        val ex1 = MediaExtractor()
        val ex2 = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ex1.setDataSource(ctx, target, null)
            ex2.setDataSource(segment.absolutePath)
            val t1 = findAudioTrack(ex1)
            val t2 = findAudioTrack(ex2)
            if (t1 < 0 || t2 < 0) throw IllegalStateException("Track audio tidak ditemukan")

            val f1 = ex1.getTrackFormat(t1)
            val f2 = ex2.getTrackFormat(t2)
            val rate = f1.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (rate != f2.getInteger(MediaFormat.KEY_SAMPLE_RATE) ||
                f1.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != f2.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            ) throw IllegalStateException("Format audio lama dan baru berbeda")

            ex1.selectTrack(t1)
            ex2.selectTrack(t2)

            val maxIn = maxOf(maxInput(f1), maxInput(f2), 256 * 1024)
            val buf = ByteBuffer.allocate(maxIn)
            val info = MediaCodec.BufferInfo()

            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val track = m.addTrack(f1)
            m.start()
            started = true

            val frameUs = 1_000_000L * 1024L / rate
            val end1 = copySamples(ex1, m, track, buf, info, 0L, frameUs)
            val first2 = ex2.sampleTime
            if (first2 < 0) throw IllegalStateException("Segmen baru kosong")
            copySamples(ex2, m, track, buf, info, end1 - first2, frameUs)

            m.stop()
            started = false
        } finally {
            if (started) { try { muxer?.stop() } catch (_: Exception) {} }
            try { muxer?.release() } catch (_: Exception) {}
            try { ex1.release() } catch (_: Exception) {}
            try { ex2.release() } catch (_: Exception) {}
        }
    }

    /** Salin semua sampel; kembalikan waktu akhir (µs) termasuk durasi 1 frame terakhir. */
    private fun copySamples(
        ex: MediaExtractor, muxer: MediaMuxer, track: Int,
        buf: ByteBuffer, info: MediaCodec.BufferInfo,
        offsetUs: Long, frameUs: Long
    ): Long {
        var last = offsetUs
        while (true) {
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            info.set(0, n, ex.sampleTime + offsetUs, ex.sampleFlags)
            muxer.writeSampleData(track, buf, info)
            last = info.presentationTimeUs + frameUs
            ex.advance()
        }
        return last
    }

    private fun maxInput(f: MediaFormat): Int =
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0

    private fun findAudioTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }
}
