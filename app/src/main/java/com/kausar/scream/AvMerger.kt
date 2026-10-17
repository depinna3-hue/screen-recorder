package com.kausar.scream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Menyatukan audio internal (WAV) ke dalam video MP4 hasil rekam layar sehingga
 * hasil akhirnya SATU file video saja:
 *  1) WAV → AAC (.m4a) memakai MediaCodec
 *  2) video + AAC digabung memakai MediaMuxer (tanpa encode ulang video)
 */
object AvMerger {

    private const val TAG = "AvMerger"
    private const val AAC = "audio/mp4a-latm"

    /** WAV PCM 16-bit buatan WavWriter (header 44 byte) → AAC di dalam wadah MP4/M4A. */
    fun wavToAac(wav: File, out: File, bitrate: Int = 128_000): Boolean {
        var raf: RandomAccessFile? = null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxStarted = false
        try {
            raf = RandomAccessFile(wav, "r")
            val hdr = ByteArray(44)
            raf.readFully(hdr)
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val ch = bb.getShort(22).toInt()
            val rate = bb.getInt(24)
            if (ch !in 1..2 || rate <= 0) return false
            if (raf.length() - 44 < 4096) return false

            val fmt = MediaFormat.createAudioFormat(AAC, rate, ch)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            val br = if (rate <= 24000) minOf(bitrate, 64_000 * ch) else bitrate
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, br)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)

            codec = MediaCodec.createEncoderByType(AAC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            val frameBytes = 2 * ch
            val tmp = ByteArray(8192 / frameBytes * frameBytes)
            var track = -1
            var inputDone = false
            var outputDone = false
            var fed = 0L
            var idle = 0
            while (!outputDone) {
                var progressed = false
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        ib.clear()
                        val want = minOf(ib.remaining(), tmp.size)
                        val n = raf.read(tmp, 0, want - want % frameBytes)
                        val pts = fed / frameBytes * 1_000_000L / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            ib.put(tmp, 0, n)
                            codec.queueInputBuffer(ii, 0, n, pts, 0)
                            fed += n
                        }
                        progressed = true
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxStarted = true
                    progressed = true
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && muxStarted && ob != null) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    progressed = true
                }
                if (progressed) idle = 0 else if (++idle > 500) throw IllegalStateException("Encoder AAC macet")
            }
            return muxStarted
        } catch (e: Exception) {
            Log.e(TAG, "wavToAac gagal", e)
            return false
        } finally {
            try { raf?.close() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            if (muxStarted) { try { muxer?.stop() } catch (_: Exception) {} }
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Gabungkan track video dari [video] dan track audio dari [audio] (m4a) ke [out].
     * [audioOffsetUs] = keterlambatan mulai audio terhadap video (≥ 0), agar tetap sinkron.
     */
    fun mux(video: File, audio: File, out: File, audioOffsetUs: Long): Boolean {
        val exV = MediaExtractor()
        val exA = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            exV.setDataSource(video.absolutePath)
            exA.setDataSource(audio.absolutePath)
            val tv = findTrack(exV, "video/")
            val ta = findTrack(exA, "audio/")
            if (tv < 0 || ta < 0) return false
            val fv = exV.getTrackFormat(tv)
            val fa = exA.getTrackFormat(ta)
            exV.selectTrack(tv)
            exA.selectTrack(ta)

            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val outV = m.addTrack(fv)
            val outA = m.addTrack(fa)
            m.start()
            started = true

            val maxIn = maxOf(
                if (fv.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fv.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0,
                2 * 1024 * 1024
            )
            val buf = ByteBuffer.allocate(maxIn)
            val info = MediaCodec.BufferInfo()
            val off = audioOffsetUs.coerceAtLeast(0L)
            var lastV = 0L
            var wroteVideo = false
            while (true) {
                val tV = exV.sampleTime
                var tA = exA.sampleTime
                if (tA >= 0) tA += off
                if (tV < 0 && tA < 0) break
                val useVideo = tV >= 0 && (tA < 0 || tV <= tA)
                buf.clear()
                if (useVideo) {
                    val n = exV.readSampleData(buf, 0)
                    if (n < 0) break
                    info.set(0, n, tV, exV.sampleFlags)
                    m.writeSampleData(outV, buf, info)
                    wroteVideo = true
                    lastV = tV
                    exV.advance()
                } else {
                    // audio yang melewati akhir video dipotong
                    if (tV < 0 && tA > lastV + 150_000L) break
                    val n = exA.readSampleData(buf, 0)
                    if (n < 0) break
                    info.set(0, n, tA, exA.sampleFlags)
                    m.writeSampleData(outA, buf, info)
                    exA.advance()
                }
            }
            return wroteVideo
        } catch (e: Exception) {
            Log.e(TAG, "mux gagal", e)
            return false
        } finally {
            if (started) { try { muxer?.stop() } catch (_: Exception) {} }
            try { muxer?.release() } catch (_: Exception) {}
            try { exV.release() } catch (_: Exception) {}
            try { exA.release() } catch (_: Exception) {}
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }
}
