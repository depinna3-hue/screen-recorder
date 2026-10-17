package com.kausar.scream

import android.Manifest
import android.app.*
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

/**
 * Service foreground untuk:
 * - Rekam layar (MediaRecorder + VirtualDisplay)
 * - Audio internal (AudioPlaybackCapture, API 29+)
 * - Mikrofon
 * - Audio gabungan internal + mikrofon (satu file WAV hasil mixing)
 *
 * Perbaikan utama:
 * - Intent kosong (mode mikrofon saja) tidak lagi dianggap punya MediaProjection
 *   (penyebab crash SecurityException di Android 14+ / perangkat tertentu).
 * - Izin RECORD_AUDIO dicek sebelum perekam dibuat; semua kegagalan jadi pesan "Gagal", bukan crash.
 * - AudioRecord stereo/sample rate yang tidak didukung otomatis turun ke konfigurasi yang didukung.
 * - Loop baca audio tidak lagi berputar tanpa henti saat AudioRecord error.
 * - Proses stop (finalisasi WAV + salin ke penyimpanan publik) dipindah dari main thread (anti-ANR).
 * - Ukuran video disesuaikan dengan kemampuan encoder H.264 perangkat.
 * - Efek (flanger/echo/reverb), skip silence, noise, AGC diperbaiki agar benar-benar berfungsi.
 */
@android.annotation.SuppressLint("NewApi")
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.kausar.scream.START"
        const val ACTION_STOP = "com.kausar.scream.STOP"
        const val ACTION_BOOKMARK = "com.kausar.scream.BOOKMARK"
        const val ACTION_PAUSE = "com.kausar.scream.PAUSE"
        const val ACTION_RESUME = "com.kausar.scream.RESUME"
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
        const val EXTRA_MIC_GAIN = "mic_gain"
        const val EXTRA_NOISE_LEVEL = "noise_level"
        const val EXTRA_EFFECT = "effect"
        const val EXTRA_ROOM_SIZE = "room_size"      // 0..1 keluasan ruangan (reverb)
        const val EXTRA_ECHO_LEVEL = "echo_level"    // 0..1 kekuatan gema (reverb)
        const val EFFECT_STUDIO = 0
        const val EFFECT_FLANGER = 1
        const val EFFECT_ECHO = 2
        const val EFFECT_REVERB = 3

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
        private const val MIN_FREE_BYTES = 30L * 1024L * 1024L
    }

    // ------------------------------------------------------------------ STATE

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null

    /** Hasil pembukaan AudioRecord (mikrofon dan/atau internal) dengan sample rate yang benar-benar dipakai. */
    private class Opened(val mic: AudioRecord?, val micCh: Int, val internal: AudioRecord?, val rate: Int)

    private class WavSession(val op: Opened, val file: File, val out: FileOutputStream) {
        @Volatile var thread: Thread? = null
        @Volatile var writer: Thread? = null
    }

    private val wavSessions = ArrayList<WavSession>()
    private val extraFiles = ArrayList<File>()
    @Volatile private var recording = false
    @Volatile private var finishing = false
    @Volatile private var paused = false
    private var destroyed = false
    private var hasProjectionFlag = false
    private var fgMic = false
    private var outputPath: String = ""
    private var startTimeMs: Long = 0L
    private var wantMp3: Boolean = false
    private var continueUri: Uri? = null
    private var continueChannels: Int = 1
    private var source: String = SOURCE_AUDIO
    @Volatile private var stoppedDurMs: Long = -1L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var forceStereo = false
    private var enableNoise = false
    private var skipSilence = false
    private var dynamicGain = false
    private var whisperMode = false
    private var micGain = 1.0f
    private var noiseLevel = 0.3f
    private var effectMode = 0
    private var roomSize = 0.95f      // keluasan ruangan 95%
    private var echoLevel = 0.20f     // gema 20%
    @Volatile private var videoStartNs = 0L
    @Volatile private var audioStartNs = 0L
    private var formatNote = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private val bookmarks = ArrayList<Long>()
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                if (recording) stopAll()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Instance baru = tidak ada rekaman lain yang hidup. Cegah flag "sedang berjalan" yang basi.
        isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                if (recording) stopAll() else if (!finishing) stopSelf()
            }
            ACTION_BOOKMARK -> {
                if (recording && startTimeMs > 0) {
                    val pos = System.currentTimeMillis() - startTimeMs
                    bookmarks.add(pos)
                    val msg = getString(R.string.bookmark_added, (pos / 1000).toInt())
                    sendStatus(msg, outputPath)
                } else if (!recording && !finishing) {
                    stopSelf()
                }
            }
            ACTION_PAUSE -> {
                if (recording && !paused) {
                    paused = true
                    try {
                        if (Build.VERSION.SDK_INT >= 24) mediaRecorder?.pause()
                    } catch (_: Exception) {}
                    updateNotification(true)
                    sendStatus(getString(R.string.status_paused), outputPath)
                } else if (!recording && !finishing) {
                    stopSelf()
                }
            }
            ACTION_RESUME -> {
                if (recording && paused) {
                    paused = false
                    try {
                        if (Build.VERSION.SDK_INT >= 24) mediaRecorder?.resume()
                    } catch (_: Exception) {}
                    updateNotification(false)
                    sendStatus(getString(R.string.status_recording), outputPath)
                } else if (!recording && !finishing) {
                    stopSelf()
                }
            }
            else -> {
                // Restart oleh sistem / intent tak dikenal: tidak ada rekaman, jangan biarkan service menggantung.
                if (!recording && !finishing) stopSelf()
            }
        }
        // Rekaman yang mati bersama proses tidak boleh dihidupkan lagi tanpa MediaProjection.
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ START

    private fun handleStart(intent: Intent) {
        if (isRunning || finishing) {
            // Sudah berjalan. Tetap panggil startForeground agar tidak kena timeout startForegroundService.
            try { enterForeground(hasProjectionFlag, fgMic) } catch (_: Exception) {}
            return
        }

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        // Intent kosong (code 0) dari mode mikrofon-saja BUKAN token MediaProjection yang valid.
        val hasProjection = code == Activity.RESULT_OK && data != null

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
        micGain = intent.getFloatExtra(EXTRA_MIC_GAIN, 1.0f).coerceIn(0.1f, 2.0f)
        noiseLevel = intent.getFloatExtra(EXTRA_NOISE_LEVEL, 0.3f).coerceIn(0f, 1f)
        effectMode = intent.getIntExtra(EXTRA_EFFECT, EFFECT_STUDIO)
        roomSize = intent.getFloatExtra(EXTRA_ROOM_SIZE, 0.95f).coerceIn(0f, 1f)
        echoLevel = intent.getFloatExtra(EXTRA_ECHO_LEVEL, 0.20f).coerceIn(0f, 1f)
        bookmarks.clear()
        formatNote = ""
        paused = false
        continueUri = intent.getStringExtra(EXTRA_CONTINUE_URI)?.let { Uri.parse(it) }
        continueChannels = intent.getIntExtra(EXTRA_CHANNELS, 1)
        source = when {
            continueUri != null -> SOURCE_CONTINUE
            cfg.isScreen -> SOURCE_SCREEN
            else -> SOURCE_AUDIO
        }
        stoppedDurMs = -1L
        outputPath = ""
        startTimeMs = 0L

        val needMic = if (cfg.isScreen) cfg.mode == RecorderConfig.SCREEN_MIC
        else cfg.mode == RecorderConfig.AUDIO_MIC || cfg.mode == RecorderConfig.AUDIO_INTERNAL_MIC
        val needInternal = if (cfg.isScreen)
            cfg.mode == RecorderConfig.SCREEN_INTERNAL || cfg.mode == RecorderConfig.SCREEN_INTERNAL_ONLY
        else cfg.mode == RecorderConfig.AUDIO_INTERNAL || cfg.mode == RecorderConfig.AUDIO_INTERNAL_MIC
        val micGranted = micPermissionGranted()

        // 1) Masuk foreground secepat mungkin (batas waktu sistem 5 detik).
        try {
            enterForeground(hasProjection, micGranted && (needMic || needInternal))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground gagal", e)
            sendStatus("Gagal: ${e.message ?: e.javaClass.simpleName}", "")
            stopSelf()
            return
        }
        acquireWakeLock()

        // 2) Siapkan perekam. Semua kegagalan dilaporkan sebagai "Gagal", tidak boleh crash.
        try {
            if ((needMic || needInternal) && !micGranted) {
                throw IllegalStateException("Izin mikrofon (RECORD_AUDIO) belum diberikan")
            }
            ensureStorage()
            if (hasProjection) {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val proj = mgr.getMediaProjection(code, data!!)
                    ?: throw IllegalStateException("Izin rekam layar tidak valid, ulangi")
                mediaProjection = proj
                // Android 14+: callback wajib didaftarkan sebelum createVirtualDisplay
                proj.registerCallback(projectionCallback, mainHandler)
            }
            if (cfg.isScreen) startScreen(cfg) else startAudio(cfg)
            startTimeMs = System.currentTimeMillis()
            wantMp3 = !cfg.isScreen && cfg.audioFormat == RecorderConfig.FMT_MP3
            isRunning = true
            sendStatus("Rekaman dimulai", outputPath)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            abortRecorders()
            sendStatus("Gagal: ${e.message ?: e.javaClass.simpleName}", "")
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun micPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureStorage() {
        val free = try { cacheDir.usableSpace } catch (_: Exception) { Long.MAX_VALUE }
        if (free < MIN_FREE_BYTES) throw IllegalStateException("Ruang penyimpanan hampir habis")
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:recording").apply {
                setReferenceCounted(false)
                acquire(6L * 60L * 60L * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    // ------------------------------------------------------------ NOTIFICATION

    private fun enterForeground(hasProjection: Boolean, withMic: Boolean) {
        hasProjectionFlag = hasProjection
        fgMic = withMic
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CH_ID, LanguageManager.wrap(this).getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        val notif = buildNotification(false)
        if (Build.VERSION.SDK_INT >= 29) {
            // Tipe hanya yang benar-benar dipakai: MEDIA_PROJECTION tanpa token / MICROPHONE tanpa izin
            // menyebabkan SecurityException di Android 14+.
            var type = 0
            if (hasProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= 30 && withMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (type == 0) {
                if (Build.VERSION.SDK_INT >= 30) {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIF_ID, notif)
                }
            } else {
                startForeground(NOTIF_ID, notif, type)
            }
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        val ctx = LanguageManager.wrap(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val pauseResumeAction = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        val pauseResumeLabel = if (isPaused) ctx.getString(R.string.notif_resume) else ctx.getString(R.string.notif_pause)
        val pauseResumePi = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(pauseResumeAction),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val title = if (isPaused) ctx.getString(R.string.notif_title_paused) else ctx.getString(R.string.notif_title)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CH_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setContentTitle(title)
            .setContentText(ctx.getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, pauseResumeLabel, pauseResumePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, ctx.getString(R.string.notif_stop), stopPi)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun updateNotification(isPaused: Boolean) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification(isPaused))
        } catch (_: Exception) {}
    }

    // ----------------------------------------------------------------- HELPERS

    private fun metrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        try {
            val dmgr = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dmgr.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(dm)
        } catch (_: Exception) {}
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
            dm.setTo(resources.displayMetrics)
        }
        return dm
    }

    private fun outDir(type: String): File {
        // Sementara di cache; setelah stop dipindah ke Musik/Movies publik
        val base = cacheDir
        if (!base.exists()) base.mkdirs()
        return base
    }

    @Suppress("DEPRECATION")
    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun needsProcessing(): Boolean =
        effectMode != EFFECT_STUDIO || enableNoise || skipSilence || dynamicGain || whisperMode ||
            kotlin.math.abs(micGain - 1f) > 0.01f

    private fun mimeFor(path: String): String = when {
        path.endsWith(".mp4", true) -> "video/mp4"
        path.endsWith(".wav", true) -> "audio/wav"
        else -> "audio/mp4"
    }

    // -------------------------------------------------------------- VIDEO SPEC

    private class VideoSpec(val w: Int, val h: Int, val fps: Int, val bitrate: Int)

    /** Cari encoder H.264 (utamakan hardware) untuk membaca batas ukuran/bitrate/fps perangkat. */
    private fun avcCaps(): MediaCodecInfo.VideoCapabilities? {
        var any: MediaCodecInfo.VideoCapabilities? = null
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals("video/avc", true) }) continue
                val caps = info.getCapabilitiesForType("video/avc").videoCapabilities ?: continue
                val soft = info.name.startsWith("OMX.google", true) || info.name.startsWith("c2.android", true)
                if (!soft) return caps
                if (any == null) any = caps
            }
        } catch (_: Exception) {}
        return any
    }

    private fun pickVideoSpec(cfg: RecorderConfig, dm: DisplayMetrics): VideoSpec {
        var (w, h) = cfg.videoSize(dm.widthPixels, dm.heightPixels)
        // Jangan lebih besar dari layar, rasio dijaga
        if (w > dm.widthPixels || h > dm.heightPixels) {
            val s = minOf(dm.widthPixels.toFloat() / w, dm.heightPixels.toFloat() / h)
            w = (w * s).toInt()
            h = (h * s).toInt()
        }
        val caps = avcCaps()
        val aw = maxOf(caps?.widthAlignment ?: 2, 2)
        val ah = maxOf(caps?.heightAlignment ?: 2, 2)
        fun align(v: Int, a: Int): Int = maxOf(a, v / a * a)
        w = align(w, aw)
        h = align(h, ah)

        var fps = 30
        var bitrate = cfg.videoBitrate().coerceAtMost(4_000_000)
        if (caps != null) {
            try {
                var n = 0
                while (n < 15 && !caps.isSizeSupported(w, h)) {
                    w = align((w * 0.85f).toInt(), aw)
                    h = align((h * 0.85f).toInt(), ah)
                    n++
                }
            } catch (_: Exception) {}
            try {
                val r = caps.getSupportedFrameRatesFor(w, h)
                fps = fps.coerceIn(r.lower.toInt().coerceAtLeast(1), r.upper.toInt().coerceAtLeast(1))
            } catch (_: Exception) {}
            try {
                val br = caps.bitrateRange
                bitrate = bitrate.coerceIn(br.lower, br.upper)
            } catch (_: Exception) {}
        }
        return VideoSpec(w, h, fps, bitrate)
    }

    // -------------------------------------------------------------- SCREEN

    private fun startScreen(cfg: RecorderConfig) {
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

        if (!needVideo) {
            startInternalAudioOnly(cfg)
            return
        }

        val dm = metrics()
        val vs = pickVideoSpec(cfg, dm)

        outputPath = File(outDir("video"), "screen_${System.currentTimeMillis()}.mp4").absolutePath

        val mr = newMediaRecorder()
        mediaRecorder = mr

        try {
            // Urutan wajib: source dulu, baru output format, lalu encoder
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (needMic) {
                mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            }

            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(outputPath)

            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setVideoSize(vs.w, vs.h)
            mr.setVideoFrameRate(vs.fps)
            mr.setVideoEncodingBitRate(vs.bitrate)

            if (needMic) {
                // Mikrofon + layar selalu mono: banyak perangkat gagal start bila diminta stereo.
                val rate = cfg.sampleRate.coerceAtMost(48000)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(cfg.safeAudioBitrate(rate, 1))
                mr.setAudioSamplingRate(rate)
                mr.setAudioChannels(1)
            }

            // Beberapa perangkat membutuhkan orientation hint
            try {
                mr.setOrientationHint(0)
            } catch (_: Exception) {}

            // Disk penuh / error encoder: hentikan dengan rapi, jangan biarkan crash
            mr.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaRecorder error what=$what extra=$extra")
                mainHandler.post { if (recording) stopAll() }
            }

            mr.prepare()

            val surface = mr.surface
                ?: throw IllegalStateException("Surface MediaRecorder null setelah prepare")

            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "KausarScreen",
                vs.w, vs.h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            ) ?: throw IllegalStateException("VirtualDisplay gagal dibuat")

            mr.start()
            videoStartNs = SystemClock.elapsedRealtimeNanos()
            recording = true

            if (needInternal) {
                val iw = File(outDir("audio"), "internal_${System.currentTimeMillis()}.wav")
                startInternalWav(cfg, iw)
                extraFiles.add(iw)
            }
        } catch (e: Exception) {
            try { mr.reset() } catch (_: Exception) {}
            try { mr.release() } catch (_: Exception) {}
            mediaRecorder = null
            throw IllegalStateException("Gagal memulai rekam layar: ${e.message}", e)
        }
    }

    /** Internal audio saja (tanpa video): selalu disimpan sebagai WAV. */
    private fun startInternalAudioOnly(cfg: RecorderConfig) {
        val f = File(outDir("audio"), "internal_${System.currentTimeMillis()}.wav")
        startInternalWav(cfg, f)
        outputPath = f.absolutePath
        recording = true
    }

    private fun startInternalWav(cfg: RecorderConfig, file: File) {
        startRecordersWav(cfg, file, needMic = false, needInternal = true)
    }

    // ------------------------------------------------------- AUDIO RECORD OPEN

    private fun createMicRecord(rate: Int, wantStereo: Boolean): Pair<AudioRecord, Int>? {
        val sources = if (whisperMode)
            intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        else intArrayOf(MediaRecorder.AudioSource.MIC)
        val chList = if (wantStereo) intArrayOf(2, 1) else intArrayOf(1)
        for (ch in chList) {
            val cfg = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val min = AudioRecord.getMinBufferSize(rate, cfg, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val buf = maxOf(min * 2, rate * ch)
            for (src in sources) {
                try {
                    val ar = AudioRecord(src, rate, cfg, AudioFormat.ENCODING_PCM_16BIT, buf)
                    if (ar.state == AudioRecord.STATE_INITIALIZED) return Pair(ar, ch)
                    ar.release()
                } catch (e: Exception) {
                    Log.w(TAG, "mic AudioRecord gagal rate=$rate ch=$ch src=$src: ${e.message}")
                }
            }
        }
        return null
    }

    private fun createInternalRecord(proj: MediaProjection, rate: Int): AudioRecord? {
        val chMask = AudioFormat.CHANNEL_IN_STEREO
        val min = AudioRecord.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val buf = maxOf(if (min > 0) min * 2 else 0, rate * 4)
        return try {
            val playbackCfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val ar = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(chMask)
                        .build()
                )
                .setBufferSizeInBytes(buf)
                .setAudioPlaybackCaptureConfig(playbackCfg)
                .build()
            if (ar.state == AudioRecord.STATE_INITIALIZED) ar else { ar.release(); null }
        } catch (e: Exception) {
            Log.w(TAG, "internal AudioRecord gagal rate=$rate: ${e.message}")
            null
        }
    }

    /**
     * Buka perekam mikrofon dan/atau internal. Jika konfigurasi diminta tidak didukung perangkat,
     * turun ke stereo→mono dan sample rate lain (kecuali [strict], mis. saat melanjutkan rekaman lama).
     */
    private fun openRecords(
        needMic: Boolean, needInternal: Boolean, reqRate: Int,
        wantStereoMic: Boolean, strict: Boolean
    ): Opened {
        val proj = mediaProjection
        if (needInternal) {
            if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Internal audio: Android 10+")
            if (proj == null) throw IllegalStateException("MediaProjection null")
        }
        val rates = if (strict) listOf(reqRate)
        else listOf(reqRate, 48000, 44100, 16000).distinct()
        var lastErr = "konfigurasi tidak didukung perangkat"
        for (r in rates) {
            var internal: AudioRecord? = null
            var mic: AudioRecord? = null
            var micCh = 1
            try {
                if (needInternal) {
                    internal = createInternalRecord(proj!!, r)
                    if (internal == null) {
                        lastErr = "audio internal tidak bisa dibuka pada $r Hz"
                        continue
                    }
                }
                if (needMic) {
                    val m = createMicRecord(r, wantStereoMic)
                    if (m == null) {
                        lastErr = "mikrofon tidak bisa dibuka pada $r Hz"
                        try { internal?.release() } catch (_: Exception) {}
                        continue
                    }
                    mic = m.first
                    micCh = m.second
                }
                return Opened(mic, micCh, internal, r)
            } catch (e: Exception) {
                try { internal?.release() } catch (_: Exception) {}
                try { mic?.release() } catch (_: Exception) {}
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }
        throw IllegalStateException("Perekam audio gagal dibuat ($lastErr)")
    }

    // ------------------------------------------------------------ WAV PIPELINE

    /** Mulai perekaman WAV: mikrofon saja, internal saja, atau keduanya dicampur jadi SATU file. */
    private fun startRecordersWav(cfg: RecorderConfig, file: File, needMic: Boolean, needInternal: Boolean) {
        val needFxStereo = forceStereo || effectMode == EFFECT_REVERB ||
            effectMode == EFFECT_ECHO || effectMode == EFFECT_FLANGER
        val op = openRecords(
            needMic, needInternal, cfg.sampleRate,
            wantStereoMic = needMic && needFxStereo,
            strict = continueUri != null
        )
        val outCh = if (needInternal || needFxStereo) 2 else 1
        startWavSession(op, file, outCh)
    }

    private fun bytesToShorts(b: ByteArray, len: Int): ShortArray {
        val s = ShortArray(len / 2)
        java.nio.ByteBuffer.wrap(b, 0, len).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
        return s
    }

    private fun shortsToBytes(s: ShortArray, count: Int): ByteArray {
        val b = ByteArray(count * 2)
        java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(s, 0, count)
        return b
    }

    /** Baca tanpa blok; kekurangan data diisi hening supaya sinkron dengan mikrofon. */
    private fun readNonBlocking(ar: AudioRecord, buf: ByteArray, wantBytes: Int) {
        var total = 0
        while (total < wantBytes) {
            val n = if (Build.VERSION.SDK_INT >= 23)
                ar.read(buf, total, wantBytes - total, AudioRecord.READ_NON_BLOCKING)
            else ar.read(buf, total, wantBytes - total)
            if (n <= 0) break
            total += n
        }
        if (total < wantBytes) java.util.Arrays.fill(buf, total, wantBytes, 0.toByte())
    }

    /** Mulai thread penulis WAV. recording harus sudah true sebelum thread jalan. */
    private fun startWavSession(op: Opened, file: File, outCh: Int) {
        val out = FileOutputStream(file)
        val sess = WavSession(op, file, out)
        wavSessions.add(sess)   // didaftarkan lebih dulu supaya abortRecorders() bisa membersihkan
        WavWriter.writeHeader(out, op.rate, outCh)
        recording = true
        op.internal?.startRecording()
        op.mic?.startRecording()
        audioStartNs = SystemClock.elapsedRealtimeNanos()
        val bad = (op.internal != null && op.internal.recordingState != AudioRecord.RECORDSTATE_RECORDING) ||
            (op.mic != null && op.mic.recordingState != AudioRecord.RECORDSTATE_RECORDING)
        if (bad) throw IllegalStateException("Perekam audio tidak bisa dimulai (mikrofon dipakai aplikasi lain?)")

        // Penangkap audio TIDAK menulis ke disk. Hasil dikirim lewat antrean ke thread penulis,
        // sehingga disk yang lambat tidak membuat buffer AudioRecord meluap (suara putus-putus / "keselek").
        val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val endMark = ByteArray(0)

        val writer = Thread({
            val bos = java.io.BufferedOutputStream(out, 256 * 1024)
            try {
                while (true) {
                    val b = queue.take()
                    if (b.isEmpty()) break
                    bos.write(b, 0, b.size)
                }
            } catch (e: Exception) {
                Log.e(TAG, "wav writer", e)
                mainHandler.post { if (recording) stopAll() }
            }
            try { bos.flush() } catch (_: Exception) {}
        }, "wav-io")
        sess.writer = writer
        writer.start()

        val t = Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            val mic = op.mic
            val internal = op.internal
            val rate = op.rate
            val micCh = op.micCh
            val blockFrames = (rate / 20).coerceAtLeast(256)       // ±50 ms per blok (latensi rendah)
            val micBuf = ByteArray(blockFrames * micCh * 2)
            val intBuf = ByteArray(blockFrames * 4)
            val fx = AudioFx(
                rate, if (mic != null) micCh else 2, effectMode,
                micGain * (if (whisperMode) 2.0f else 1.0f),
                enableNoise, noiseLevel, dynamicGain, roomSize, echoLevel
            )
            val widen = mic != null && micCh == 1 && outCh == 2
            var silenceRun = 0
            var failed = false
            try {
                while (recording) {
                    var micS: ShortArray? = null
                    var intS: ShortArray? = null
                    var frames = 0
                    if (mic != null) {
                        val n = mic.read(micBuf, 0, micBuf.size)
                        if (n < 0) { failed = recording; break }       // error fatal: jangan berputar tanpa henti
                        val usable = n - (n % (micCh * 2))
                        if (usable <= 0) { Thread.sleep(5); continue }
                        frames = usable / (micCh * 2)
                        micS = bytesToShorts(micBuf, usable)
                        if (internal != null) {
                            readNonBlocking(internal, intBuf, frames * 4)
                            intS = bytesToShorts(intBuf, frames * 4)
                        }
                    } else {
                        val n = internal!!.read(intBuf, 0, intBuf.size)
                        if (n < 0) { failed = recording; break }
                        val usable = n - (n % 4)
                        if (usable <= 0) { Thread.sleep(5); continue }
                        frames = usable / 4
                        intS = bytesToShorts(intBuf, usable)
                    }
                    if (paused) continue

                    val src = micS ?: intS!!
                    val srcCount = frames * (if (micS != null) micCh else 2)
                    val peak = fx.peak(src, srcCount)
                    fx.updateAgc(peak)
                    val peakAll = if (micS != null && intS != null) maxOf(peak, fx.peak(intS, frames * 2)) else peak

                    if (skipSilence) {
                        if (peakAll < 800) {
                            silenceRun += frames
                            if (silenceRun > rate / 5) continue      // hening > ±200 ms: dilewati
                        } else {
                            silenceRun = 0
                        }
                    }

                    // Efek/gain/noise hanya pada suara mikrofon (atau internal bila itu satu-satunya sumber)
                    val processed = fx.process(src, frames, widen)
                    if (micS != null && intS != null) {
                        val m = minOf(processed.size, intS.size)
                        for (i in 0 until m) {
                            processed[i] = (processed[i] + intS[i]).coerceIn(-32768, 32767).toShort()
                        }
                    }
                    queue.offer(shortsToBytes(processed, processed.size))
                }
            } catch (e: Exception) {
                Log.e(TAG, "wav loop", e)
                failed = recording
            } finally {
                queue.offer(endMark)
            }
            if (failed) mainHandler.post { if (recording) stopAll() }
        }, "wav-capture")
        sess.thread = t
        t.start()
    }

    /** Pemroses audio: gain, AGC, noise lembut, dan efek flanger/echo/reverb (per kanal). */
    private class AudioFx(
        private val rate: Int,
        private val channels: Int,
        private val effect: Int,
        private val gain: Float,
        private val noiseOn: Boolean,
        private val noiseLevel: Float,
        private val agcOn: Boolean,
        roomSize: Float,      // 0..1 : keluasan ruangan (reverb)
        echoLevel: Float      // 0..1 : kekuatan gema (reverb)
    ) {
        private val delayLen = (rate * 0.4).toInt().coerceAtLeast(256)
        private val delay = Array(channels) { ShortArray(delayLen) }
        private var pos = 0
        private var phase = 0.0
        private var agcGain = 1.0f
        private var lastGain = gain
        private var seed = 12345L

        // Flanger: delay 1–5 ms, LFO ±0,35 Hz
        private val lfoInc = 2.0 * Math.PI * 0.35 / rate
        private val flangeBase = rate * 0.003
        private val flangeDepth = rate * 0.002
        private val echoDelay = (rate * 0.12).toInt().coerceIn(1, delayLen - 1)

        // Reverb: semakin luas ruangan, semakin jauh jarak pantulan
        private val sc = 0.5 + roomSize.coerceIn(0f, 1f) * 1.5
        private val d1 = (rate * 0.025 * sc).toInt().coerceIn(1, delayLen - 1)
        private val d2 = (rate * 0.045 * sc).toInt().coerceIn(1, delayLen - 1)
        private val d3 = (rate * 0.070 * sc).toInt().coerceIn(1, delayLen - 1)
        private val d4 = (rate * 0.095 * sc).toInt().coerceIn(1, delayLen - 1)
        private val fb = (echoLevel.coerceIn(0f, 1f) * 3f).coerceAtMost(0.85f)
        private val widenBack = (d1 / 2).coerceIn(12, delayLen - 1)
        private val widenMix = if (effect == EFFECT_REVERB) 0.15f + 0.30f * roomSize.coerceIn(0f, 1f) else 0.15f

        fun peak(s: ShortArray, count: Int): Int {
            var p = 0
            for (i in 0 until count) {
                val a = kotlin.math.abs(s[i].toInt())
                if (a > p) p = a
            }
            return p
        }

        fun updateAgc(peak: Int) {
            if (!agcOn) return
            if (peak < 2000) agcGain = (agcGain * 1.02f).coerceAtMost(8f)
            else if (peak > 12000) agcGain = (agcGain * 0.95f).coerceAtLeast(1f)
        }

        /**
         * [src] berisi [frames] frame interleaved sebanyak [channels] kanal.
         * Bila [widen] (sumber mono → keluaran stereo), kanal kanan dibuat dari tap delay agar lebar.
         */
        fun process(src: ShortArray, frames: Int, widen: Boolean): ShortArray {
            val outCh = if (widen) 2 else channels
            val out = ShortArray(frames * outCh)
            // Gain diubah mulus sepanjang blok (tanpa lompatan) agar tidak terdengar "memompa"/tersendat
            val gStart = lastGain
            val gEnd = gain * agcGain
            lastGain = gEnd
            val noiseAmp = if (noiseOn && noiseLevel > 0f) noiseLevel * 0.04f else 0f
            var si = 0
            var oi = 0
            for (f in 0 until frames) {
                val g = gStart + (gEnd - gStart) * f / frames
                val fd = if (effect == EFFECT_FLANGER)
                    (flangeBase + flangeDepth * kotlin.math.sin(phase)).toInt().coerceIn(1, delayLen - 1)
                else 0
                for (c in 0 until channels) {
                    var v = src[si].toFloat() * g
                    si++
                    if (noiseAmp > 0f) {
                        seed = seed * 1103515245L + 12345L
                        val raw = ((seed shr 16) and 0x7FFF).toInt() - 16384
                        v += raw * noiseAmp
                    }
                    val d = delay[c]
                    when (effect) {
                        EFFECT_FLANGER -> {
                            v = v * 0.6f + d[(pos - fd + delayLen) % delayLen] * 0.4f
                        }
                        EFFECT_ECHO -> {
                            v = v * 0.75f + d[(pos - echoDelay + delayLen) % delayLen] * 0.55f
                        }
                        EFFECT_REVERB -> {
                            val wet = d[(pos - d1 + delayLen) % delayLen] * 0.4f +
                                d[(pos - d2 + delayLen) % delayLen] * 0.3f +
                                d[(pos - d3 + delayLen) % delayLen] * 0.2f +
                                d[(pos - d4 + delayLen) % delayLen] * 0.1f
                            v = v * (1f - 0.25f * fb) + wet * fb
                        }
                    }
                    val o = v.toInt().coerceIn(-32768, 32767).toShort()
                    if (widen && c == 0) {
                        val r = (o * (1f - widenMix) + d[(pos - widenBack + delayLen) % delayLen] * widenMix)
                            .toInt().coerceIn(-32768, 32767)
                        out[oi++] = o
                        out[oi++] = r.toShort()
                    } else {
                        out[oi++] = o
                    }
                    d[pos] = o
                }
                pos = (pos + 1) % delayLen
                if (effect == EFFECT_FLANGER) {
                    phase += lfoInc
                    if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                }
            }
            return out
        }
    }

    // -------------------------------------------------------------- AUDIO

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
                // internal + mikrofon: DICAMPUR menjadi satu file WAV (bukan dua file terpisah)
                val f = File(outDir("audio"), "mix_${System.currentTimeMillis()}.wav")
                startRecordersWav(cfg, f, needMic = true, needInternal = true)
                outputPath = f.absolutePath
                formatNote = "Audio internal + mikrofon digabung dalam satu file WAV."
            }
        }
        recording = true
    }

    private fun startMicFile(cfg: RecorderConfig) {
        val useWav = cfg.audioFormat == RecorderConfig.FMT_WAV || cfg.audioFormat == RecorderConfig.FMT_MP3 ||
            needsProcessing()
        if (useWav) {
            val f = File(outDir("audio"), "mic_${System.currentTimeMillis()}.wav")
            outputPath = f.absolutePath
            startRecordersWav(cfg, f, needMic = true, needInternal = false)
            if (cfg.audioFormat == RecorderConfig.FMT_AAC && continueUri == null) {
                formatNote = "Efek/gain/noise/skip silence aktif, jadi rekaman disimpan sebagai WAV."
            }
        } else {
            startMicM4a(cfg)
        }
    }

    /** Mikrofon → AAC (.m4a) lewat MediaRecorder; turun ke mono / sample rate lain bila perangkat menolak. */
    private fun startMicM4a(cfg: RecorderConfig) {
        outputPath = File(outDir("audio"), "mic_${System.currentTimeMillis()}.m4a").absolutePath
        val wantCh = if (forceStereo || (continueUri != null && continueChannels > 1)) 2 else 1
        val chList = if (wantCh == 2) intArrayOf(2, 1) else intArrayOf(1)
        val rates = if (continueUri != null) listOf(cfg.sampleRate)
        else listOf(cfg.sampleRate, 44100, 48000).distinct()
        var lastErr: Exception? = null
        for (ch in chList) {
            for (rate in rates) {
                val mr = newMediaRecorder()
                try {
                    mr.setAudioSource(
                        if (whisperMode) MediaRecorder.AudioSource.VOICE_RECOGNITION
                        else MediaRecorder.AudioSource.MIC
                    )
                    mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    mr.setAudioEncodingBitRate(cfg.safeAudioBitrate(rate, ch))
                    mr.setAudioSamplingRate(rate)
                    mr.setAudioChannels(ch)
                    mr.setOutputFile(outputPath)
                    mr.setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaRecorder error what=$what extra=$extra")
                        mainHandler.post { if (recording) stopAll() }
                    }
                    mr.prepare()
                    mr.start()
                    mediaRecorder = mr
                    return
                } catch (e: Exception) {
                    lastErr = e
                    Log.w(TAG, "m4a gagal ch=$ch rate=$rate: ${e.message}")
                    try { mr.reset() } catch (_: Exception) {}
                    try { mr.release() } catch (_: Exception) {}
                }
            }
        }
        throw IllegalStateException("Mikrofon gagal dimulai: ${lastErr?.message ?: "tidak diketahui"}", lastErr)
    }

    // ------------------------------------------------------------ STOP / ABORT

    private fun closeSession(s: WavSession, finalizeHeader: Boolean) {
        // stop() lebih dulu supaya read() yang sedang menunggu langsung kembali
        try { s.op.mic?.stop() } catch (_: Exception) {}
        try { s.op.internal?.stop() } catch (_: Exception) {}
        val th = s.thread
        if (th != null) {
            try { th.join(3000) } catch (_: Exception) {}
            if (th.isAlive) {
                try { th.interrupt() } catch (_: Exception) {}
                try { th.join(1000) } catch (_: Exception) {}
            }
        }
        val wr = s.writer
        if (wr != null) {
            try { wr.join(8000) } catch (_: Exception) {}
            if (wr.isAlive) { try { wr.interrupt() } catch (_: Exception) {} ; try { wr.join(1000) } catch (_: Exception) {} }
        }
        try { s.op.mic?.release() } catch (_: Exception) {}
        try { s.op.internal?.release() } catch (_: Exception) {}
        try { s.out.flush() } catch (_: Exception) {}
        try { s.out.close() } catch (_: Exception) {}
        if (finalizeHeader) {
            try { WavWriter.finalize(s.file) } catch (_: Exception) {}
        }
    }

    /** Bersihkan semua perekam bila start gagal di tengah jalan. */
    private fun abortRecorders() {
        recording = false
        val sessions = ArrayList(wavSessions)
        wavSessions.clear()
        for (sess in sessions) {
            closeSession(sess, false)
            try { sess.file.delete() } catch (_: Exception) {}
        }
        for (x in extraFiles) { try { x.delete() } catch (_: Exception) {} }
        extraFiles.clear()
        try { if (outputPath.isNotEmpty()) File(outputPath).delete() } catch (_: Exception) {}
        mediaRecorder?.let {
            try { it.reset() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
        releaseWakeLock()
    }

    /** Hentikan rekaman. Pekerjaan berat (finalisasi, salin file) berjalan di thread terpisah. */
    private fun stopAll() {
        if (finishing || !recording) return
        finishing = true
        stoppedDurMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0L
        recording = false
        Thread({ finishRecording(false) }, "rec-finish").start()
    }

    private fun finishRecording(inline: Boolean) {
        var finalPath = outputPath
        var finalUri = ""
        val notes = ArrayList<String>()
        try {
            val sessions = ArrayList(wavSessions)
            wavSessions.clear()
            for (s in sessions) closeSession(s, true)

            var recorderOk = true
            val mr = mediaRecorder
            mediaRecorder = null
            if (mr != null) {
                try { mr.stop() } catch (e: Exception) {
                    recorderOk = false
                    Log.w(TAG, "MediaRecorder.stop gagal", e)
                }
                try { mr.reset() } catch (_: Exception) {}
                try { mr.release() } catch (_: Exception) {}
            }
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
            try {
                mediaProjection?.unregisterCallback(projectionCallback)
                mediaProjection?.stop()
            } catch (_: Exception) {}
            mediaProjection = null

            val cu = continueUri
            if (cu != null) {
                // Mode "Lanjutkan rekaman": gabungkan segmen baru ke file lama
                finalPath = ""
                finalUri = cu.toString()
                val seg = File(outputPath)
                try {
                    if (outputPath.isNotEmpty() && seg.exists() && seg.length() > 0) {
                        val r = AudioAppender.append(this, cu, seg)
                        finalPath = r.displayPath
                        finalUri = r.uri?.toString() ?: finalUri
                        if (r.note.isNotEmpty()) notes.add(r.note)
                    } else {
                        notes.add("Tidak ada audio baru yang terekam.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "append", e)
                    notes.add("Gagal melanjutkan: ${e.message}")
                }
            } else {
                // Rekam layar + audio internal: satukan ke dalam SATU file video
                if (outputPath.endsWith(".mp4", true) && extraFiles.isNotEmpty() && recorderOk) {
                    if (mergeInternalAudio()) notes.add("Audio internal sudah digabung ke dalam video.")
                }
                // Pindahkan ke folder publik Musik / Movies (bukan data app)
                finalPath = outputPath
                if (outputPath.isNotEmpty()) {
                    val f = File(outputPath)
                    val isVideo = outputPath.endsWith(".mp4", true)
                    val isWav = outputPath.endsWith(".wav", true)
                    val minLen = if (isWav) 44L else 0L
                    // stop() gagal pada rekaman < 1 detik → file MP4/M4A tidak valid
                    val tooShort = !isWav && !recorderOk && stoppedDurMs in 0..1200
                    if (f.exists() && f.length() > minLen && !tooShort) {
                        try {
                            val pub = PublicStorage.publishFileEx(this, f, isVideo, mimeFor(outputPath))
                            finalPath = pub.displayPath
                            finalUri = pub.uri?.toString() ?: ""
                        } catch (e: Exception) {
                            Log.e(TAG, "publish", e)
                            finalPath = outputPath + " (gagal pindah publik: ${e.message})"
                        }
                    } else {
                        try { f.delete() } catch (_: Exception) {}
                        finalPath = ""
                        notes.add("Rekaman terlalu pendek atau kosong, tidak disimpan.")
                    }
                }
                // File tambahan (WAV audio internal pada rekam layar) → terbitkan terpisah ke Musik
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
                if (extraPaths.isNotEmpty()) {
                    notes.add("Audio internal disimpan sebagai file terpisah:\n" + extraPaths.joinToString("\n"))
                }
                if (formatNote.isNotEmpty()) notes.add(formatNote)
                if (bookmarks.isNotEmpty()) {
                    val bmText = bookmarks.joinToString(", ") { "${it / 1000}s" }
                    notes.add("Bookmark: $bmText")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "finishRecording", e)
        }

        val path = finalPath
        val uri = finalUri
        val note = notes.joinToString("\n")
        val deliver = Runnable {
            releaseWakeLock()
            isRunning = false
            sendStatus("Rekaman selesai", path, uri, note)
            if (!destroyed) {
                stopForegroundCompat()
                stopSelf()
            }
            finishing = false
        }
        if (inline) deliver.run() else mainHandler.post(deliver)
    }

    /** Gabungkan WAV audio internal ke MP4. true = berhasil (WAV dihapus), false = biarkan terpisah. */
    private fun mergeInternalAudio(): Boolean {
        val video = File(outputPath)
        val wav = extraFiles.firstOrNull() ?: return false
        if (!video.exists() || video.length() <= 0 || !wav.exists() || wav.length() <= 44 + 4096) return false
        val ts = System.currentTimeMillis()
        val aac = File(cacheDir, "mix_audio_$ts.m4a")
        val merged = File(cacheDir, "merged_$ts.mp4")
        return try {
            if (!AvMerger.wavToAac(wav, aac)) return false
            val offUs = ((audioStartNs - videoStartNs) / 1000L).coerceAtLeast(0L)
            if (!AvMerger.mux(video, aac, merged, offUs) || merged.length() <= 0) return false
            merged.copyTo(video, overwrite = true)
            try { wav.delete() } catch (_: Exception) {}
            extraFiles.remove(wav)
            true
        } catch (e: Exception) {
            Log.e(TAG, "mergeInternalAudio", e)
            false
        } finally {
            try { aac.delete() } catch (_: Exception) {}
            try { merged.delete() } catch (_: Exception) {}
        }
    }

    private fun sendStatus(msg: String, path: String, uri: String = "", note: String = "") {
        try {
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
        } catch (e: Exception) {
            Log.w(TAG, "sendStatus gagal", e)
        }
    }

    override fun onDestroy() {
        destroyed = true
        if (recording && !finishing) {
            // Service dimatikan sistem saat merekam: selamatkan hasil rekaman secara sinkron
            finishing = true
            stoppedDurMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0L
            recording = false
            try { finishRecording(true) } catch (_: Throwable) {}
        }
        releaseWakeLock()
        super.onDestroy()
    }
}
