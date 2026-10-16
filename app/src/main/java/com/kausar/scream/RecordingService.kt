package com.kausar.scream

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/**
 * Service foreground untuk:
 * - Rekam layar (MediaRecorder + VirtualDisplay)
 * - Audio internal (AudioPlaybackCapture, API 29+)
 * - Mikrofon
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.kausar.scream.START"
        const val ACTION_STOP = "com.kausar.scream.STOP"
        const val ACTION_BOOKMARK = "com.kausar.scream.BOOKMARK"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_IS_SCREEN = "is_screen"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SAMPLE_RATE = "sample_rate"
        const val EXTRA_AUDIO_QUALITY = "audio_quality"
        const val EXTRA_VIDEO_QUALITY = "video_quality"
        const val EXTRA_AUDIO_FORMAT = "audio_format"
        const val EXTRA_FORCE_STEREO = "force_stereo"
        const val EXTRA_NOISE = "noise"
        const val EXTRA_SKIP_SILENCE = "skip_silence"
        const val EXTRA_DYNAMIC_GAIN = "dynamic_gain"
        const val EXTRA_WHISPER = "whisper"

        const val BROADCAST_STATUS = "com.kausar.scream.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PATH = "path"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_WANT_MP3 = "want_mp3"

        // --- Lanjutkan rekaman & pemutaran ---
        const val EXTRA_CONTINUE_URI = "continue_uri"
        const val EXTRA_CHANNELS = "channels"
        const val EXTRA_URI = "uri"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_NOTE = "note"
        const val SOURCE_SCREEN = "screen"
        const val SOURCE_AUDIO = "audio"
        const val SOURCE_CONTINUE = "continue"

        @Volatile
        var isRunning = false

        private const val CH_ID = "rec_channel"
        private const val NOTIF_ID = 4401
        private const val TAG = "RecordingService"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private class WavSession(val record: AudioRecord, val file: File, val out: FileOutputStream) {
        var thread: Thread? = null
    }
    private val wavSessions = ArrayList<WavSession>()
    private val extraFiles = ArrayList<File>()
    private var recording = false
    private var outputPath: String = ""
    private var startTimeMs: Long = 0L
    private var wantMp3: Boolean = false
    private var continueUri: Uri? = null
    private var continueChannels: Int = 1
    private var source: String = SOURCE_AUDIO
    private var stoppedDurMs: Long = -1L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var forceStereo = false
    private var enableNoise = false
    private var skipSilence = false
    private var dynamicGain = false
    private var whisperMode = false
    private val bookmarks = ArrayList<Long>()
    private var noiseSeed = 12345L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (isRunning) return START_STICKY
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val cfg = RecorderConfig(
                    mode = intent.getIntExtra(EXTRA_MODE, 0),
                    sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 44100),
                    audioQuality = intent.getIntExtra(EXTRA_AUDIO_QUALITY, 1),
                    videoQuality = intent.getIntExtra(EXTRA_VIDEO_QUALITY, 1),
                    audioFormat = intent.getIntExtra(EXTRA_AUDIO_FORMAT, RecorderConfig.FMT_AAC),
                    isScreen = intent.getBooleanExtra(EXTRA_IS_SCREEN, true)
                )
                forceStereo = intent.getBooleanExtra(EXTRA_FORCE_STEREO, false)
                enableNoise = intent.getBooleanExtra(EXTRA_NOISE, false)
                skipSilence = intent.getBooleanExtra(EXTRA_SKIP_SILENCE, false)
                dynamicGain = intent.getBooleanExtra(EXTRA_DYNAMIC_GAIN, false)
                whisperMode = intent.getBooleanExtra(EXTRA_WHISPER, false)
                bookmarks.clear()
                continueUri = intent.getStringExtra(EXTRA_CONTINUE_URI)?.let { Uri.parse(it) }
                continueChannels = intent.getIntExtra(EXTRA_CHANNELS, 1)
                source = when {
                    continueUri != null -> SOURCE_CONTINUE
                    cfg.isScreen -> SOURCE_SCREEN
                    else -> SOURCE_AUDIO
                }
                stoppedDurMs = -1L
                startForegroundNotification(data != null)
                if (data != null) {
                    val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mgr.getMediaProjection(code, data)
                }
                try {
                    if (cfg.isScreen) startScreen(cfg) else startAudio(cfg)
                    startTimeMs = System.currentTimeMillis()
                    wantMp3 = !cfg.isScreen && cfg.audioFormat == RecorderConfig.FMT_MP3
                    isRunning = true
                    sendStatus("Rekaman dimulai", outputPath)
                } catch (e: Exception) {
                    Log.e(TAG, "start failed", e)
                    abortRecorders()
                    sendStatus("Gagal: ${e.message}", "")
                    stopSelf()
                }
            }
            ACTION_STOP -> if (recording) stopAll()
            ACTION_BOOKMARK -> {
                if (recording && startTimeMs > 0) {
                    val pos = System.currentTimeMillis() - startTimeMs
                    bookmarks.add(pos)
                    sendStatus("Bookmark ditambahkan di ${pos / 1000} detik", outputPath)
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification(hasProjection: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CH_ID, "Rekaman", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val notif = Notification.Builder(this, CH_ID)
            .setContentTitle("Sedang merekam")
            .setContentText("Ketuk notifikasi di app untuk berhenti")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 34 && !hasProjection) {
            // Android 14+: tipe mediaProjection hanya boleh dipakai setelah izin rekam layar diberikan.
            // Rekam mikrofon saja (termasuk "lanjutkan rekaman") cukup memakai tipe microphone.
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun metrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun outDir(type: String): File {
        // Sementara di cache; setelah stop dipindah ke Musik/Movies publik
        val base = cacheDir
        if (!base.exists()) base.mkdirs()
        return base
    }

    // -------------------- SCREEN --------------------
    private fun startScreen(cfg: RecorderConfig) {
        val dm = metrics()
        val (vw, vh) = cfg.videoSize(dm.widthPixels, dm.heightPixels)
        val needVideo = cfg.mode != RecorderConfig.SCREEN_INTERNAL_ONLY
        val needMic = cfg.mode == RecorderConfig.SCREEN_MIC
        val needInternal = cfg.mode == RecorderConfig.SCREEN_INTERNAL ||
            cfg.mode == RecorderConfig.SCREEN_INTERNAL_ONLY

        if (needInternal && Build.VERSION.SDK_INT < 29) {
            throw IllegalStateException("Audio internal butuh Android 10+")
        }
        if ((needVideo || needInternal) && mediaProjection == null) {
            throw IllegalStateException("Izin rekam layar belum diberikan")
        }

        // Internal-only tanpa video → rekam audio saja lewat projection
        if (!needVideo) {
            startInternalAudioOnly(cfg)
            return
        }

        outputPath = File(outDir("video"), "screen_${System.currentTimeMillis()}.mp4").absolutePath

        @Suppress("DEPRECATION")
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        mediaRecorder = mr

        if (needMic && !needInternal) {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        } else if (needInternal) {
            // Audio di-capture terpisah / lewat playback capture ke MediaRecorder sulit digabung;
            // pakai MIC sebagai cadangan + AudioRecord internal paralel tidak ke file yang sama.
            // Untuk internal+layar: MediaRecorder video tanpa audio mic, audio internal ke track terpisah rumit.
            // Pendekatan praktis: MediaRecorder VIDEO + AUDIO dari VOICE_RECOGNITION/MIC,
            // dan bila internal: gunakan AudioPlaybackCapture ke AAC file samping — user minta satu file.
            // Satu file MP4 dengan internal: setAudioSource tidak bisa playback capture langsung.
            // Gunakan MediaRecorder hanya video, internal audio dicampur tidak di sini.
            // Android: gunakan MediaRecorder dengan audio source MIC jika mic, else video only + internal wav.
        }

        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        if (needMic) {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        }

        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setOutputFile(outputPath)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mr.setVideoSize(vw, vh)
        mr.setVideoFrameRate(30)
        mr.setVideoEncodingBitRate(cfg.videoBitrate())
        if (needMic) {
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(cfg.audioBitrate())
            mr.setAudioSamplingRate(cfg.sampleRate)
            mr.setAudioChannels(1)
        }
        mr.prepare()

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "KausarScreen",
            vw, vh, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mr.surface, null, null
        )

        // Internal audio sebagai file WAV samping bila dipilih (satu sesi)
        if (needInternal) {
            val iw = File(outDir("audio"), "internal_${System.currentTimeMillis()}.wav")
            startInternalWav(cfg, iw)
            extraFiles.add(iw) // diterbitkan terpisah saat stop
        }

        mr.start()
        recording = true
    }

    /** Internal audio saja (tanpa video): selalu disimpan sebagai WAV. */
    private fun startInternalAudioOnly(cfg: RecorderConfig) {
        val f = File(outDir("audio"), "internal_${System.currentTimeMillis()}.wav")
        startInternalWav(cfg, f)
        outputPath = f.absolutePath
        recording = true
    }

    /** Mulai thread penulis WAV. recording harus sudah true sebelum thread jalan. */
    private fun startWavSession(ar: AudioRecord, file: File, rate: Int, channels: Int, bufSize: Int) {
        val out = FileOutputStream(file)
        WavWriter.writeHeader(out, rate, channels)
        val sess = WavSession(ar, file, out)
        wavSessions.add(sess)
        recording = true
        ar.startRecording()
        val inChannels = try {
            if (Build.VERSION.SDK_INT >= 24 && ar.channelCount > 0) ar.channelCount else 1
        } catch (_: Exception) { 1 }
        sess.thread = Thread {
            val buf = ByteArray(bufSize)
            var silenceRun = 0
            val silenceThresh = if (skipSilence) 800 else 0
            var gain = 1.0f
            try {
                while (recording && !Thread.interrupted()) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var writeBuf = buf
                    var writeLen = n

                    if (enableNoise || dynamicGain || skipSilence || (forceStereo && inChannels == 1 && channels == 2)) {
                        val samples = ShortArray(n / 2)
                        java.nio.ByteBuffer.wrap(buf, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                        var peak = 0
                        for (i in samples.indices) {
                            val a = kotlin.math.abs(samples[i].toInt())
                            if (a > peak) peak = a
                        }

                        if (dynamicGain) {
                            if (peak < 2000) gain = (gain * 1.02f).coerceAtMost(8f)
                            else if (peak > 12000) gain = (gain * 0.95f).coerceAtLeast(1f)
                        }

                        if (skipSilence) {
                            if (peak < silenceThresh) {
                                silenceRun++
                                if (silenceRun > rate / 50) continue
                            } else {
                                silenceRun = 0
                            }
                        }

                        for (i in samples.indices) {
                            var s = samples[i].toInt()
                            if (dynamicGain) s = (s * gain).toInt().coerceIn(-32768, 32767)
                            if (enableNoise) {
                                noiseSeed = noiseSeed * 1103515245L + 12345L
                                val noise = ((noiseSeed shr 16) and 0x7FFF).toInt() - 16384
                                s = (s + noise / 8).coerceIn(-32768, 32767)
                            }
                            samples[i] = s.toShort()
                        }

                        if (forceStereo && inChannels == 1 && channels == 2) {
                            val stereo = ShortArray(samples.size * 2)
                            for (i in samples.indices) {
                                stereo[i * 2] = samples[i]
                                stereo[i * 2 + 1] = samples[i]
                            }
                            writeBuf = ByteArray(stereo.size * 2)
                            java.nio.ByteBuffer.wrap(writeBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(stereo)
                            writeLen = writeBuf.size
                        } else {
                            writeBuf = ByteArray(samples.size * 2)
                            java.nio.ByteBuffer.wrap(writeBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
                            writeLen = writeBuf.size
                        }
                    }

                    synchronized(sess) { out.write(writeBuf, 0, writeLen) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "wav loop", e)
            }
        }.also { it.start() }
    }

    private fun startInternalWav(cfg: RecorderConfig, file: File) {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Internal audio: Android 10+")
        val proj = mediaProjection ?: throw IllegalStateException("MediaProjection null")

        val rate = cfg.sampleRate
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
        val bufSize = minBuf.coerceAtLeast(rate) * 2

        val playbackCfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val ar = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(rate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setAudioPlaybackCaptureConfig(playbackCfg)
            .build()

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw IllegalStateException("AudioRecord internal gagal init")
        }
        startWavSession(ar, file, rate, 2, bufSize)
    }

    // -------------------- AUDIO (mic / internal / both) --------------------
    private fun startAudio(cfg: RecorderConfig) {
        val needMic = cfg.mode == RecorderConfig.AUDIO_MIC || cfg.mode == RecorderConfig.AUDIO_INTERNAL_MIC
        val needInternal = cfg.mode == RecorderConfig.AUDIO_INTERNAL || cfg.mode == RecorderConfig.AUDIO_INTERNAL_MIC

        if (needInternal && Build.VERSION.SDK_INT < 29) {
            throw IllegalStateException("Audio internal butuh Android 10+")
        }
        if (needInternal && mediaProjection == null) {
            throw IllegalStateException("Audio internal perlu izin rekam layar (MediaProjection)")
        }

        when {
            needInternal && !needMic -> {
                // internal saja
                val f = File(outDir("audio"), "audio_${System.currentTimeMillis()}.wav")
                startInternalWav(cfg, f)
                outputPath = f.absolutePath
            }
            needMic && !needInternal -> {
                startMicFile(cfg)
            }
            else -> {
                // internal + mikrofon: dua file terpisah (mikrofon = utama, internal = tambahan)
                startMicFile(cfg)
                val f = File(outDir("audio"), "internal_${System.currentTimeMillis()}.wav")
                startInternalWav(cfg, f)
                extraFiles.add(f)
            }
        }
        recording = true
    }

    private fun startMicFile(cfg: RecorderConfig) {
        if (cfg.audioFormat == RecorderConfig.FMT_WAV || cfg.audioFormat == RecorderConfig.FMT_MP3) {
            val f = File(outDir("audio"), "mic_${System.currentTimeMillis()}.wav")
            outputPath = f.absolutePath
            val rate = cfg.sampleRate
            val channelConfig = if (forceStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val outChannels = if (forceStereo) 2 else 1
            val minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = minBuf.coerceAtLeast(rate) * 2
            val source = if (whisperMode) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC
            val ar = try {
                AudioRecord(source, rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            } catch (_: Exception) {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            }
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                throw IllegalStateException("Mic AudioRecord gagal")
            }
            startWavSession(ar, f, rate, outChannels, bufSize)
        } else {
            outputPath = File(outDir("audio"), "mic_${System.currentTimeMillis()}.m4a").absolutePath
            @Suppress("DEPRECATION")
            val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            mediaRecorder = mr
            mr.setAudioSource(if (whisperMode) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(cfg.audioBitrate())
            mr.setAudioSamplingRate(cfg.sampleRate)
            val ch = if (forceStereo || (continueUri != null && continueChannels > 1)) 2 else 1
            mr.setAudioChannels(ch)
            mr.setOutputFile(outputPath)
            mr.prepare()
            mr.start()
        }
    }

    /** Bersihkan semua perekam bila start gagal di tengah jalan. */
    private fun abortRecorders() {
        recording = false
        for (sess in wavSessions) {
            try { sess.record.stop() } catch (_: Exception) {}
            try { sess.record.release() } catch (_: Exception) {}
            try { sess.out.close() } catch (_: Exception) {}
            try { sess.file.delete() } catch (_: Exception) {}
        }
        wavSessions.clear()
        for (x in extraFiles) { try { x.delete() } catch (_: Exception) {} }
        extraFiles.clear()
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun stopAll() {
        stoppedDurMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0L
        recording = false
        for (sess in wavSessions) {
            try { sess.thread?.join(500) } catch (_: Exception) {}
            try { sess.record.stop() } catch (_: Exception) {}
            try { sess.record.release() } catch (_: Exception) {}
            try { sess.out.close() } catch (_: Exception) {}
            try { WavWriter.finalize(sess.file) } catch (_: Exception) {}
        }
        wavSessions.clear()
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        // Mode "Lanjutkan rekaman": gabungkan segmen baru ke file lama (di thread terpisah)
        val cu = continueUri
        if (cu != null) {
            val segPath = outputPath
            Thread {
                var path = ""
                var uri = cu.toString()
                var note = ""
                try {
                    val seg = File(segPath)
                    if (segPath.isNotEmpty() && seg.exists() && seg.length() > 0) {
                        val r = AudioAppender.append(this@RecordingService, cu, seg)
                        path = r.displayPath
                        uri = r.uri?.toString() ?: uri
                        note = r.note
                    } else {
                        note = "Tidak ada audio baru yang terekam."
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "append", e)
                    note = "Gagal melanjutkan: ${e.message}"
                }
                mainHandler.post {
                    isRunning = false
                    sendStatus("Rekaman selesai", path, uri, note)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }.start()
            return
        }

        // Pindahkan ke folder publik Musik / Movies (bukan data app)
        var finalPath = outputPath
        var finalUri = ""
        try {
            if (outputPath.isNotEmpty()) {
                val f = File(outputPath)
                if (f.exists() && f.length() > 0) {
                    val isVideo = outputPath.endsWith(".mp4", true)
                    val mime = when {
                        isVideo -> "video/mp4"
                        outputPath.endsWith(".wav", true) -> "audio/wav"
                        else -> "audio/mp4"
                    }
                    val pub = PublicStorage.publishFileEx(this, f, isVideo, mime)
                    finalPath = pub.displayPath
                    finalUri = pub.uri?.toString() ?: ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "publish", e)
            finalPath = outputPath + " (gagal pindah publik: ${e.message})"
        }
        // File tambahan (WAV audio internal) → terbitkan terpisah ke Musik
        val extraPaths = ArrayList<String>()
        for (x in extraFiles) {
            try {
                if (x.exists() && x.length() > 44) {
                    extraPaths.add(PublicStorage.publishFileEx(this, x, false, "audio/wav").displayPath)
                } else {
                    x.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "publish extra", e)
            }
        }
        extraFiles.clear()
        var extraNote = if (extraPaths.isEmpty()) "" else
            "Audio internal disimpan sebagai file terpisah:\n" + extraPaths.joinToString("\n")
        if (bookmarks.isNotEmpty()) {
            val bmText = bookmarks.joinToString(", ") { "${it / 1000}s" }
            extraNote = (if (extraNote.isEmpty()) "" else extraNote + "\n") + "Bookmark: $bmText"
        }

        isRunning = false
        sendStatus("Rekaman selesai", finalPath, finalUri, extraNote)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendStatus(msg: String, path: String, uri: String = "", note: String = "") {
        val i = Intent(BROADCAST_STATUS)
        i.setPackage(packageName)
        i.putExtra(EXTRA_STATUS, msg)
        i.putExtra(EXTRA_PATH, path)
        i.putExtra(EXTRA_URI, uri)
        i.putExtra(EXTRA_NOTE, note)
        i.putExtra(EXTRA_SOURCE, source)
        val dur = when {
            stoppedDurMs >= 0 -> stoppedDurMs
            startTimeMs > 0 -> System.currentTimeMillis() - startTimeMs
            else -> 0L
        }
        i.putExtra(EXTRA_DURATION_MS, dur)
        i.putExtra(EXTRA_WANT_MP3, wantMp3)
        sendBroadcast(i)
    }

    override fun onDestroy() {
        if (recording) stopAll()
        super.onDestroy()
    }
}
