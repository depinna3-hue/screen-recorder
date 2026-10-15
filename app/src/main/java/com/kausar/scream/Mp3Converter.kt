package com.kausar.scream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Setelah rekaman (WAV PCM) selesai, konversi ke format terpilih.
 * - Prioritas encoder: audio/mpeg (MP3) bila ada di perangkat
 * - Cadangan: AAC dalam wadah .m4a (hampir semua HP)
 * Callback progress 0..100
 */
object Mp3Converter {
    private const val TAG = "Mp3Converter"

    data class Result(val ok: Boolean, val outFile: File?, val message: String)

    fun convertWavToCompressed(
        wavFile: File,
        preferMp3: Boolean,
        onProgress: (Int) -> Unit
    ): Result {
        if (!wavFile.exists() || wavFile.length() < 44) {
            return Result(false, null, "File WAV tidak valid")
        }
        return try {
            val header = ByteArray(44)
            FileInputStream(wavFile).use { it.read(header) }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(22)
            val channels = bb.short.toInt()
            val sampleRate = bb.int
            val bits = run {
                bb.position(34)
                bb.short.toInt()
            }
            if (bits != 16) return Result(false, null, "Hanya mendukung WAV PCM 16-bit")

            val pcmBytes = wavFile.length() - 44
            val totalSamples = (pcmBytes / (channels * 2)).toInt().coerceAtLeast(1)

            val mimeMp3 = "audio/mpeg"
            val mimeAac = MediaFormat.MIMETYPE_AUDIO_AAC
            var usedMime = mimeAac
            var encoder: MediaCodec? = null

            if (preferMp3) {
                try {
                    encoder = MediaCodec.createEncoderByType(mimeMp3)
                    usedMime = mimeMp3
                } catch (_: Exception) {
                    encoder = null
                }
            }
            if (encoder == null) {
                encoder = MediaCodec.createEncoderByType(mimeAac)
                usedMime = mimeAac
            }

            val bitRate = 128000
            val format = MediaFormat.createAudioFormat(usedMime, sampleRate, channels)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            val outExt = if (usedMime == mimeMp3) "mp3" else "m4a"
            val outFile = File(wavFile.parentFile, wavFile.nameWithoutExtension + "_conv.$outExt")
            if (outFile.exists()) outFile.delete()

            // MediaMuxer hanya untuk AAC/m4a; untuk mp3 tulis stream mentah dari codec bila bisa
            if (usedMime == mimeAac) {
                encodeAacToM4a(encoder, format, wavFile, outFile, channels, sampleRate, totalSamples, onProgress)
            } else {
                encodeMpegStream(encoder, format, wavFile, outFile, channels, totalSamples, onProgress)
            }

            // hapus wav sementara
            try { wavFile.delete() } catch (_: Exception) {}

            val label = if (outExt == "mp3") "MP3" else "AAC (.m4a) — perangkat tidak punya encoder MP3"
            Result(true, outFile, "Konversi selesai ($label)")
        } catch (e: Exception) {
            Log.e(TAG, "convert", e)
            Result(false, null, "Konversi gagal: ${e.message}")
        }
    }

    private fun encodeAacToM4a(
        encoder: MediaCodec,
        format: MediaFormat,
        wavFile: File,
        outFile: File,
        channels: Int,
        sampleRate: Int,
        totalSamples: Int,
        onProgress: (Int) -> Unit
    ) {
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val frameSamples = 1024
        val frameBytes = frameSamples * channels * 2
        val buf = ByteArray(frameBytes)
        var ptsUs = 0L
        var samplesDone = 0

        FileInputStream(wavFile).use { fis ->
            fis.skip(44)
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val inIx = encoder.dequeueInputBuffer(10000)
                    if (inIx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIx)!!
                        inBuf.clear()
                        val n = fis.read(buf)
                        if (n <= 0) {
                            encoder.queueInputBuffer(inIx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            inBuf.put(buf, 0, n)
                            encoder.queueInputBuffer(inIx, 0, n, ptsUs, 0)
                            val adv = n / (channels * 2)
                            ptsUs += adv * 1_000_000L / sampleRate
                            samplesDone += adv
                            onProgress((samplesDone * 100 / totalSamples).coerceIn(0, 99))
                        }
                    }
                }
                val info = MediaCodec.BufferInfo()
                val outIx = encoder.dequeueOutputBuffer(info, 10000)
                when {
                    outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIx)!!
                        if (info.size > 0 && muxerStarted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, outBuf, info)
                        }
                        encoder.releaseOutputBuffer(outIx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                if (inputDone && outIx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // drain a bit more
                    val info2 = MediaCodec.BufferInfo()
                    val o2 = encoder.dequeueOutputBuffer(info2, 5000)
                    if (o2 < 0 && inputDone) break
                }
            }
        }
        try { encoder.stop() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
        if (muxerStarted) try { muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
        onProgress(100)
    }

    private fun encodeMpegStream(
        encoder: MediaCodec,
        format: MediaFormat,
        wavFile: File,
        outFile: File,
        channels: Int,
        totalSamples: Int,
        onProgress: (Int) -> Unit
    ) {
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val fos = outFile.outputStream()
        val frameSamples = 1152
        val frameBytes = frameSamples * channels * 2
        val buf = ByteArray(frameBytes)
        var ptsUs = 0L
        var samplesDone = 0
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        FileInputStream(wavFile).use { fis ->
            fis.skip(44)
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val inIx = encoder.dequeueInputBuffer(10000)
                    if (inIx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIx)!!
                        inBuf.clear()
                        val n = fis.read(buf)
                        if (n <= 0) {
                            encoder.queueInputBuffer(inIx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            inBuf.put(buf, 0, n)
                            encoder.queueInputBuffer(inIx, 0, n, ptsUs, 0)
                            val adv = n / (channels * 2)
                            ptsUs += adv * 1_000_000L / sampleRate
                            samplesDone += adv
                            onProgress((samplesDone * 100 / totalSamples).coerceIn(0, 99))
                        }
                    }
                }
                val info = MediaCodec.BufferInfo()
                val outIx = encoder.dequeueOutputBuffer(info, 10000)
                if (outIx >= 0) {
                    val outBuf = encoder.getOutputBuffer(outIx)!!
                    if (info.size > 0) {
                        val chunk = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(chunk)
                        fos.write(chunk)
                    }
                    encoder.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (inputDone) break
            }
        }
        fos.close()
        try { encoder.stop() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
        onProgress(100)
    }
}