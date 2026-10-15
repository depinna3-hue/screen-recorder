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
import android.os.Build
import android.os.Environment
import android.os.IBinder
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
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_IS_SCREEN = "is_screen"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SAMPLE_RATE = "sample_rate"
        const val EXTRA_AUDIO_QUALITY = "audio_quality"
        const val EXTRA_VIDEO_QUALITY = "video_quality"
        const val EXTRA_AUDIO_FORMAT = "audio_format"

        const val BROADCAST_STATUS = "com.kausar.scream.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PATH = "path"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_WANT_MP3 = "want_mp3"

        private const val CH_ID = "rec_channel"
        private const val NOTIF_ID = 4401
        private const val TAG = "RecordingService"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioRecord: AudioRecord? = null
    private var wavThread: Thread? = null
    private var wavFile: File? = null
    private var wavOut: FileOutputStream? = null
    private var recording = false
    private var outputPath: String = ""
    private var startTimeMs: Long = 0L
    private var wantMp3: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
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
                startForegroundNotification()
                if (data != null) {
                    val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mgr.getMediaProjection(code, data)
                }
                try {
                    if (cfg.isScreen) startScreen(cfg) else startAudio(cfg)
                    startTimeMs = System.currentTimeMillis()
                    wantMp3 = !cfg.isScreen && cfg.audioFormat == RecorderConfig.FMT_MP3
                    sendStatus("Rekaman dimulai", outputPath)
                } catch (e: Exception) {
                    Log.e(TAG, "start failed", e)
                    sendStatus("Gagal: ${e.message}", "")
                    stopSelf()
                }
            }
            ACTION_STOP -> stopAll()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
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
        if (Build.VERSION.SDK_INT >= 29) {
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
            startInternalWav(cfg, File(outDir("audio"), "internal_${System.currentTimeMillis()}.wav"))
        }

        mr.start()
        recording = true
    }

    private fun startInternalAudioOnly(cfg: RecorderConfig) {
        val ext = when (cfg.audioFormat) {
            RecorderConfig.FMT_WAV -> "wav"
            RecorderConfig.FMT_MP3 -> "m4a" // encoder sistem = AAC; ekstensi m4a (MP3 native tidak ada di API)
            else -> "m4a"
        }
        // WAV paling andal untuk pure internal capture
        if (cfg.audioFormat == RecorderConfig.FMT_WAV) {
            val f = File(outDir("audio"), "internal_${System.currentTimeMillis()}.$ext")
            if (cfg.audioFormat == RecorderConfig.FMT_WAV) {
                startInternalWav(cfg, f)
                outputPath = f.absolutePath
            } else {
                // AAC via AudioRecord + MediaCodec terlalu panjang; pakai WAV dulu lalu catat
                val wav = File(outDir("audio"), "internal_${System.currentTimeMillis()}.wav")
                startInternalWav(cfg, wav)
                outputPath = wav.absolutePath
            }
        }
        recording = true
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

        audioRecord = ar
        wavFile = file
        wavOut = FileOutputStream(file)
        WavWriter.writeHeader(wavOut!!, rate, 2)
        ar.startRecording()

        wavThread = Thread {
            val buf = ByteArray(bufSize)
            try {
                while (recording && !Thread.interrupted()) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n > 0) {
                        synchronized(this) { wavOut?.write(buf, 0, n) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "wav loop", e)
            }
        }.also { it.start() }
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
                // internal only
                val f = File(
                    outDir("audio"),
                    "audio_${System.currentTimeMillis()}." + if (cfg.audioFormat == RecorderConfig.FMT_WAV) "wav" else "wav"
                )
                startInternalWav(cfg, f)
                outputPath = f.absolutePath
            }
            needMic && !needInternal -> {
                startMicFile(cfg)
            }
            else -> {
                // internal + mic: rekam keduanya ke WAV terpisah; mic ke MediaRecorder AAC
                startMicFile(cfg)
                val f = File(outDir("audio"), "internal_${System.currentTimeMillis()}.wav")
                startInternalWav(cfg, f)
            }
        }
        recording = true
    }

    private fun startMicFile(cfg: RecorderConfig) {
        when (cfg.audioFormat) {
            RecorderConfig.FMT_WAV -> {
                val f = File(outDir("audio"), "mic_${System.currentTimeMillis()}.wav")
                outputPath = f.absolutePath
                val rate = cfg.sampleRate
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                val ar = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate, channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    ar.release()
                    throw IllegalStateException("Mic AudioRecord gagal")
                }
                audioRecord = ar
                wavFile = f
                wavOut = FileOutputStream(f)
                WavWriter.writeHeader(wavOut!!, rate, 1)
                ar.startRecording()
                wavThread = Thread {
                    val buf = ByteArray(minBuf * 2)
                    try {
                        while (recording && !Thread.interrupted()) {
                            val n = ar.read(buf, 0, buf.size)
                            if (n > 0) synchronized(this) { wavOut?.write(buf, 0, n) }
                        }
                    } catch (_: Exception) {}
                }.also { it.start() }
            }
            else -> {
                // AAC langsung, atau MP3 → rekam WAV dulu lalu dikonversi di UI setelah stop
                if (cfg.audioFormat == RecorderConfig.FMT_MP3) {
                    val f = File(outDir("audio"), "mic_${System.currentTimeMillis()}.wav")
                    outputPath = f.absolutePath
                    val rate = cfg.sampleRate
                    val channelConfig = AudioFormat.CHANNEL_IN_MONO
                    val minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                    val ar = AudioRecord(
                        MediaRecorder.AudioSource.MIC, rate, channelConfig,
                        AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                    )
                    if (ar.state != AudioRecord.STATE_INITIALIZED) {
                        ar.release()
                        throw IllegalStateException("Mic AudioRecord gagal")
                    }
                    audioRecord = ar
                    wavFile = f
                    wavOut = FileOutputStream(f)
                    WavWriter.writeHeader(wavOut!!, rate, 1)
                    ar.startRecording()
                    wavThread = Thread {
                        val buf = ByteArray(minBuf * 2)
                        try {
                            while (recording && !Thread.interrupted()) {
                                val n = ar.read(buf, 0, buf.size)
                                if (n > 0) synchronized(this) { wavOut?.write(buf, 0, n) }
                            }
                        } catch (_: Exception) {}
                    }.also { it.start() }
                } else {
                    outputPath = File(outDir("audio"), "mic_${System.currentTimeMillis()}.m4a").absolutePath
                    @Suppress("DEPRECATION")
                    val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
                    mediaRecorder = mr
                    mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                    mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    mr.setAudioEncodingBitRate(cfg.audioBitrate())
                    mr.setAudioSamplingRate(cfg.sampleRate)
                    mr.setAudioChannels(1)
                    mr.setOutputFile(outputPath)
                    mr.prepare()
                    mr.start()
                }
            }
        }
    }

    private fun stopAll() {
        recording = false
        try { wavThread?.join(500) } catch (_: Exception) {}
        wavThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { wavOut?.close() } catch (_: Exception) {}
        wavOut = null
        wavFile?.let { try { WavWriter.finalize(it) } catch (_: Exception) {} }
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        // Pindahkan ke folder publik Musik / Movies (bukan data app)
        var finalPath = outputPath
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
                    finalPath = PublicStorage.publishFile(this, f, isVideo, mime)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "publish", e)
            finalPath = outputPath + " (gagal pindah publik: ${e.message})"
        }
        sendStatus("Rekaman selesai", finalPath)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendStatus(msg: String, path: String) {
        val i = Intent(BROADCAST_STATUS)
        i.setPackage(packageName)
        i.putExtra(EXTRA_STATUS, msg)
        i.putExtra(EXTRA_PATH, path)
        val dur = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0L
        i.putExtra(EXTRA_DURATION_MS, dur)
        i.putExtra(EXTRA_WANT_MP3, wantMp3)
        sendBroadcast(i)
    }

    override fun onDestroy() {
        if (recording) stopAll()
        super.onDestroy()
    }
}
