package com.kausar.scream

import android.app.*
import android.app.PendingIntent
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
import android.os.PowerManager
import java.io.IOException
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
class RecordingService : Service(), MediaRecorder.OnErrorListener {

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
        const val EXTRA_FILTER_MODE = "filter_mode"
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
    @Volatile private var paused = false
    private var hasProjectionFlag = false
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
    private var micGain = 1.0f
    private var noiseLevel = 0.3f
    private var effectMode = 0
    private var filterMode = FilterMode.ORIGINAL
    private var systemFx: SystemAudioEffects? = null
    private val bookmarks = ArrayList<Long>()
    private var noiseSeed = 12345L
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                if (recording) stopAll()
            }
        }
    }
    /** Cegah CPU sleep saat merekam (pola MiCode/SoundRecorder). */
    private var mWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            mWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:KausarRecorder"
            ).also { it.setReferenceCounted(false) }
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLowMemory() {
        if (recording) stopAll()
        super.onLowMemory()
    }

    override fun onError(mr: MediaRecorder?, what: Int, extra: Int) {
        Log.e(TAG, "MediaRecorder onError what=$what extra=$extra")
        mainHandler.post {
            if (recording) {
                try { stopAll() } catch (_: Exception) {}
                sendStatus("Gagal: MediaRecorder error ($what)", "")
            }
        }
    }

    /** True bila sedang panggilan — rekaman mic biasanya gagal (pola SoundRecorder). */
    private fun isInCall(): Boolean {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {
            false
        }
    }

    private fun acquireWakeLock() {
        try {
            if (mWakeLock?.isHeld != true) mWakeLock?.acquire(6 * 60 * 60 * 1000L) // max 6 jam
        } catch (_: Exception) {}
    }

    private fun attachSystemFx(ar: android.media.AudioRecord) {
        try {
            systemFx?.releaseEffects()
            systemFx = SystemAudioEffects(ar.audioSessionId).also { it.applyMode(filterMode) }
        } catch (e: Exception) {
            Log.e(TAG, "systemFx", e)
        }
    }

    private fun releaseSystemFx() {
        try { systemFx?.releaseEffects() } catch (_: Exception) {}
        systemFx = null
    }

    private fun releaseWakeLock() {
        try {
            if (mWakeLock?.isHeld == true) mWakeLock?.release()
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (isRunning) return START_STICKY
                if (isInCall()) {
                    sendStatus("Gagal: sedang dalam panggilan telepon", "")
                    stopSelf()
                    return START_NOT_STICKY
                }
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
                micGain = intent.getFloatExtra(EXTRA_MIC_GAIN, 1.0f).coerceIn(0.1f, 2.0f)
                noiseLevel = intent.getFloatExtra(EXTRA_NOISE_LEVEL, 0.3f).coerceIn(0f, 1f)
                effectMode = intent.getIntExtra(EXTRA_EFFECT, EFFECT_STUDIO)
                val fm = intent.getIntExtra(EXTRA_FILTER_MODE, 0)
                filterMode = FilterMode.values().getOrElse(fm) { FilterMode.ORIGINAL }
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
                    try {
                        mediaProjection = mgr.getMediaProjection(code, data)
                    } catch (e: Exception) {
                        throw IllegalStateException("MediaProjection gagal: ${e.message}", e)
                    }
                    if (Build.VERSION.SDK_INT >= 34) {
                        try {
                            mediaProjection?.registerCallback(projectionCallback, mainHandler)
                        } catch (_: Exception) {}
                    }
                }
                try {
                    if (cfg.isScreen) startScreen(cfg) else startAudio(cfg)
                    startTimeMs = System.currentTimeMillis()
                    wantMp3 = !cfg.isScreen && cfg.audioFormat == RecorderConfig.FMT_MP3
                    isRunning = true
                    acquireWakeLock()
                    sendStatus("Rekaman dimulai", outputPath)
                } catch (e: Exception) {
                    Log.e(TAG, "start failed", e)
                    abortRecorders()
                    releaseWakeLock()
                    sendStatus("Gagal: ${e.message ?: e.javaClass.simpleName}", "")
                    stopSelf()
                }
            }
            ACTION_STOP -> if (recording) stopAll()
            ACTION_BOOKMARK -> {
                if (recording && startTimeMs > 0) {
                    val pos = System.currentTimeMillis() - startTimeMs
                    bookmarks.add(pos)
                    val msg = getString(R.string.bookmark_added, (pos / 1000).toInt())
                    sendStatus(msg, outputPath)
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
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification(hasProjection: Boolean) {
        hasProjectionFlag = hasProjection
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CH_ID, LanguageManager.wrap(this).getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val notif = buildNotification(false)
        if (Build.VERSION.SDK_INT >= 34 && !hasProjection) {
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
        return builder.build()
    }

    private fun updateNotification(isPaused: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(isPaused))
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
        var (vw, vh) = cfg.videoSize(dm.widthPixels, dm.heightPixels)
        // Pastikan genap dan tidak terlalu besar untuk kompatibilitas
        if (vw % 2 != 0) vw++
        if (vh % 2 != 0) vh++
        if (vw > dm.widthPixels) vw = dm.widthPixels / 2 * 2
        if (vh > dm.heightPixels) vh = dm.heightPixels / 2 * 2

        // Mode layar: SELALU satu file MP4 (video + audio mic menyatu). Tidak ada file audio terpisah.
        val needVideo = cfg.mode != RecorderConfig.SCREEN_INTERNAL_ONLY

        if (mediaProjection == null) {
            throw IllegalStateException("Izin rekam layar belum diberikan")
        }

        if (!needVideo) {
            // Hanya audio internal, tanpa video
            if (Build.VERSION.SDK_INT < 29) {
                throw IllegalStateException("Audio internal butuh Android 10+")
            }
            startInternalAudioOnly(cfg)
            return
        }

        extraFiles.clear()
        outputPath = File(outDir("video"), "screen_${System.currentTimeMillis()}.mp4").absolutePath

        @Suppress("DEPRECATION")
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        mediaRecorder = mr

        // Coba beberapa resolusi bila perangkat menolak ukuran awal
        val candidates = listOf(
            vw to vh,
            (vw / 2 * 2).coerceAtLeast(640) to (vh / 2 * 2).coerceAtLeast(360),
            1280 to 720,
            854 to 480,
            640 to 360
        ).distinct()

        var lastErr: Exception? = null
        for ((tryW, tryH) in candidates) {
            var w = if (tryW % 2 != 0) tryW + 1 else tryW
            var h = if (tryH % 2 != 0) tryH + 1 else tryH
            try {
                try { mr.reset() } catch (_: Exception) {}
                // Satu file MP4: video layar + audio mikrofon menyatu
                mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setOutputFile(outputPath)
                mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                mr.setVideoSize(w, h)
                mr.setVideoFrameRate(30)
                mr.setVideoEncodingBitRate(
                    when {
                        h >= 1080 -> 3_500_000
                        h >= 720 -> 2_000_000
                        else -> 1_200_000
                    }
                )
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(cfg.audioBitrate().coerceAtLeast(64_000))
                mr.setAudioSamplingRate(44100)
                mr.setAudioChannels(1)
                try { mr.setOrientationHint(0) } catch (_: Exception) {}
                mr.setOnErrorListener(this)
                try {
                    mr.prepare()
                } catch (e: IOException) {
                    throw IllegalStateException("prepare layar gagal: ${e.message}", e)
                }
                val surface = mr.surface
                    ?: throw IllegalStateException("Surface MediaRecorder null setelah prepare")
                virtualDisplay = try {
                    mediaProjection!!.createVirtualDisplay(
                        "KausarScreen", w, h, dm.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, null
                    )
                } catch (_: Exception) {
                    mediaProjection!!.createVirtualDisplay(
                        "KausarScreen", w, h, dm.densityDpi,
                        0, surface, null, null
                    )
                }
                if (virtualDisplay == null) {
                    throw IllegalStateException("VirtualDisplay null")
                }
                // Tidak menambah file audio terpisah
                try {
                    mr.start()
                } catch (e: RuntimeException) {
                    throw IllegalStateException(
                        if (isInCall()) "sedang dalam panggilan" else "start layar gagal: ${e.message}",
                        e
                    )
                }
                recording = true
                lastErr = null
                break
            } catch (e: Exception) {
                lastErr = e
                Log.e(TAG, "screen try ${w}x${h} failed", e)
                try { virtualDisplay?.release() } catch (_: Exception) {}
                virtualDisplay = null
                try { mr.reset() } catch (_: Exception) {}
            }
        }
        if (lastErr != null || !recording) {
            try { mr.release() } catch (_: Exception) {}
            mediaRecorder = null
            throw IllegalStateException("Gagal memulai rekam layar: ${lastErr?.message}", lastErr)
        }
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
            var agcGain = 1.0f
            // Buffer delay untuk efek (echo/reverb/flanger) — ~120ms max
            val delayLen = (rate * 0.25).toInt().coerceAtLeast(256)
            val delayBuf = ShortArray(delayLen)
            var delayPos = 0
            var flangePhase = 0.0
            try {
                while (recording && !Thread.interrupted()) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (paused) continue
                    var writeBuf = buf
                    var writeLen = n

                    val samples = ShortArray(n / 2)
                    java.nio.ByteBuffer.wrap(buf, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                    var peak = 0
                    for (i in samples.indices) {
                        val a = kotlin.math.abs(samples[i].toInt())
                        if (a > peak) peak = a
                    }

                    if (dynamicGain) {
                        if (peak < 2000) agcGain = (agcGain * 1.02f).coerceAtMost(8f)
                        else if (peak > 12000) agcGain = (agcGain * 0.95f).coerceAtLeast(1f)
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
                        var s = samples[i].toFloat()
                        // Mic gain slider
                        s *= micGain
                        if (dynamicGain) s *= agcGain

                        // Soft noise (level dari slider)
                        if (enableNoise && noiseLevel > 0f) {
                            noiseSeed = noiseSeed * 1103515245L + 12345L
                            val raw = ((noiseSeed shr 16) and 0x7FFF).toInt() - 16384
                            s += raw * noiseLevel * 0.04f
                        }

                        // Efek audio
                        when (effectMode) {
                            EFFECT_FLANGER -> {
                                flangePhase += 0.0008
                                val flangeDelay = (8 + 6 * kotlin.math.sin(flangePhase)).toInt().coerceIn(1, delayLen - 1)
                                val di = (delayPos - flangeDelay + delayLen) % delayLen
                                s = s * 0.7f + delayBuf[di] * 0.3f
                            }
                            EFFECT_ECHO -> {
                                val echoDelay = (rate * 0.12).toInt().coerceIn(1, delayLen - 1)
                                val di = (delayPos - echoDelay + delayLen) % delayLen
                                s = s * 0.75f + delayBuf[di] * 0.55f
                            }
                            EFFECT_REVERB -> {
                                // Gema stereo-like multi-tap (V8 style), lebih basah
                                val d1 = (rate * 0.025).toInt().coerceIn(1, delayLen - 1)
                                val d2 = (rate * 0.045).toInt().coerceIn(1, delayLen - 1)
                                val d3 = (rate * 0.07).toInt().coerceIn(1, delayLen - 1)
                                val d4 = (rate * 0.095).toInt().coerceIn(1, delayLen - 1)
                                val a1 = delayBuf[(delayPos - d1 + delayLen) % delayLen]
                                val a2 = delayBuf[(delayPos - d2 + delayLen) % delayLen]
                                val a3 = delayBuf[(delayPos - d3 + delayLen) % delayLen]
                                val a4 = delayBuf[(delayPos - d4 + delayLen) % delayLen]
                                s = s * 0.5f + a1 * 0.28f + a2 * 0.18f + a3 * 0.12f + a4 * 0.08f
                            }
                        }

                        val outS = s.toInt().coerceIn(-32768, 32767).toShort()
                        delayBuf[delayPos] = outS
                        delayPos = (delayPos + 1) % delayLen
                        samples[i] = outS
                    }

                    // Stereo: mono → L/R; reverb selalu stereo lebar
                    val wantStereoOut = channels == 2 && (forceStereo || effectMode == EFFECT_REVERB || effectMode == EFFECT_ECHO || effectMode == EFFECT_FLANGER)
                    if (wantStereoOut && inChannels == 1) {
                        val stereo = ShortArray(samples.size * 2)
                        for (i in samples.indices) {
                            stereo[i * 2] = samples[i]
                            val di = (delayPos - (if (effectMode == EFFECT_REVERB) 48 else 12) + delayLen) % delayLen
                            val mix = if (effectMode == EFFECT_REVERB) 0.35f else 0.15f
                            val r = (samples[i] * (1f - mix) + delayBuf[di] * mix).toInt().coerceIn(-32768, 32767)
                            stereo[i * 2 + 1] = r.toShort()
                        }
                        writeBuf = ByteArray(stereo.size * 2)
                        java.nio.ByteBuffer.wrap(writeBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(stereo)
                        writeLen = writeBuf.size
                    } else {
                        writeBuf = ByteArray(samples.size * 2)
                        java.nio.ByteBuffer.wrap(writeBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
                        writeLen = writeBuf.size
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
                val f = File(outDir("audio"), "audio_${System.currentTimeMillis()}.wav")
                startInternalWav(cfg, f)
                outputPath = f.absolutePath
            }
            needMic && !needInternal -> {
                startMicFile(cfg)
            }
            else -> {
                // internal + mikrofon: SATU file gabungan (mix), bukan dua file terpisah
                startMixedMicInternal(cfg)
            }
        }
        recording = true
    }

    /** Rekam mic + internal ke satu file WAV stereo/mono dengan mix. */
    private fun startMixedMicInternal(cfg: RecorderConfig) {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Audio internal: Android 10+")
        val proj = mediaProjection ?: throw IllegalStateException("MediaProjection null")
        val rate = cfg.sampleRate
        val outChannels = if (forceStereo || effectMode != EFFECT_STUDIO) 2 else 1
        val f = File(outDir("audio"), "mix_${System.currentTimeMillis()}.wav")
        outputPath = f.absolutePath

        // Mic
        val micCfg = AudioFormat.CHANNEL_IN_MONO
        val micBuf = AudioRecord.getMinBufferSize(rate, micCfg, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(rate) * 2
        val micAr = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, micCfg, AudioFormat.ENCODING_PCM_16BIT, micBuf)
        } catch (_: Exception) {
            throw IllegalStateException("Mic gagal init")
        }
        if (micAr.state != AudioRecord.STATE_INITIALIZED) {
            micAr.release()
            throw IllegalStateException("Mic AudioRecord gagal")
        }

        // Internal playback capture
        val intCfg = AudioFormat.CHANNEL_IN_STEREO
        val intBuf = AudioRecord.getMinBufferSize(rate, intCfg, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(rate) * 2
        val playbackCfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val intAr = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(intCfg)
                    .build()
            )
            .setBufferSizeInBytes(intBuf)
            .setAudioPlaybackCaptureConfig(playbackCfg)
            .build()
        if (intAr.state != AudioRecord.STATE_INITIALIZED) {
            micAr.release()
            intAr.release()
            throw IllegalStateException("Internal AudioRecord gagal init")
        }

        val out = FileOutputStream(f)
        WavWriter.writeHeader(out, rate, outChannels)
        val sess = WavSession(micAr, f, out) // track mic for cleanup; release intAr in thread end
        wavSessions.add(sess)
        // Also track internal for cleanup via extra list of records - store on sess thread
        recording = true
        attachSystemFx(micAr)
        try { micAr.startRecording() } catch (e: Exception) {
            releaseSystemFx()
            micAr.release(); intAr.release(); out.close()
            throw e
        }
        try { intAr.startRecording() } catch (e: Exception) {
            try { micAr.stop() } catch (_: Exception) {}
            micAr.release(); intAr.release(); out.close()
            throw e
        }

        val delayLen = (rate * 0.25).toInt().coerceAtLeast(256)
        val delayBuf = ShortArray(delayLen)
        var delayPos = 0
        var flangePhase = 0.0
        var agcGain = 1.0f

        sess.thread = Thread {
            val micRaw = ByteArray(micBuf)
            val intRaw = ByteArray(intBuf)
            try {
                while (recording && !Thread.interrupted()) {
                    if (paused) {
                        try { Thread.sleep(20) } catch (_: Exception) {}
                        continue
                    }
                    val nm = micAr.read(micRaw, 0, micRaw.size)
                    val ni = intAr.read(intRaw, 0, intRaw.size)
                    if (nm <= 0 && ni <= 0) continue

                    val micSamples = if (nm > 0) {
                        ShortArray(nm / 2).also {
                            java.nio.ByteBuffer.wrap(micRaw, 0, nm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
                        }
                    } else ShortArray(0)
                    val intSamples = if (ni > 0) {
                        ShortArray(ni / 2).also {
                            java.nio.ByteBuffer.wrap(intRaw, 0, ni).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
                        }
                    } else ShortArray(0)

                    // internal stereo -> mono average for mix base
                    val intMono = if (intSamples.isEmpty()) ShortArray(micSamples.size)
                    else {
                        val frames = intSamples.size / 2
                        ShortArray(frames.coerceAtLeast(1)) { i ->
                            val L = intSamples.getOrElse(i * 2) { 0 }
                            val R = intSamples.getOrElse(i * 2 + 1) { L }
                            ((L.toInt() + R.toInt()) / 2).toShort()
                        }
                    }
                    val n = maxOf(micSamples.size, intMono.size).coerceAtLeast(1)
                    val mixed = ShortArray(n)
                    for (i in 0 until n) {
                        val m = micSamples.getOrElse(i) { 0 }.toInt()
                        val inn = intMono.getOrElse(i) { 0 }.toInt()
                        var s = (m * 0.55f + inn * 0.55f)
                        s *= micGain
                        if (dynamicGain) {
                            val a = kotlin.math.abs(s.toInt())
                            if (a < 2000) agcGain = (agcGain * 1.02f).coerceAtMost(8f)
                            else if (a > 12000) agcGain = (agcGain * 0.95f).coerceAtLeast(1f)
                            s *= agcGain
                        }
                        if (enableNoise && noiseLevel > 0f) {
                            noiseSeed = noiseSeed * 1103515245L + 12345L
                            val raw = ((noiseSeed shr 16) and 0x7FFF).toInt() - 16384
                            s += raw * noiseLevel * 0.04f
                        }
                        when (effectMode) {
                            EFFECT_FLANGER -> {
                                flangePhase += 0.0008
                                val fd = (8 + 6 * kotlin.math.sin(flangePhase)).toInt().coerceIn(1, delayLen - 1)
                                val di = (delayPos - fd + delayLen) % delayLen
                                s = s * 0.7f + delayBuf[di] * 0.3f
                            }
                            EFFECT_ECHO -> {
                                val ed = (rate * 0.12).toInt().coerceIn(1, delayLen - 1)
                                val di = (delayPos - ed + delayLen) % delayLen
                                s = s * 0.75f + delayBuf[di] * 0.55f
                            }
                            EFFECT_REVERB -> {
                                val d1 = (rate * 0.025).toInt().coerceIn(1, delayLen - 1)
                                val d2 = (rate * 0.045).toInt().coerceIn(1, delayLen - 1)
                                val d3 = (rate * 0.07).toInt().coerceIn(1, delayLen - 1)
                                val d4 = (rate * 0.095).toInt().coerceIn(1, delayLen - 1)
                                s = s * 0.5f +
                                    delayBuf[(delayPos - d1 + delayLen) % delayLen] * 0.28f +
                                    delayBuf[(delayPos - d2 + delayLen) % delayLen] * 0.18f +
                                    delayBuf[(delayPos - d3 + delayLen) % delayLen] * 0.12f +
                                    delayBuf[(delayPos - d4 + delayLen) % delayLen] * 0.08f
                            }
                        }
                        val outS = s.toInt().coerceIn(-32768, 32767).toShort()
                        delayBuf[delayPos] = outS
                        delayPos = (delayPos + 1) % delayLen
                        mixed[i] = outS
                    }

                    val writeBuf: ByteArray
                    val writeLen: Int
                    if (outChannels == 2) {
                        val stereo = ShortArray(mixed.size * 2)
                        for (i in mixed.indices) {
                            stereo[i * 2] = mixed[i]
                            val di = (delayPos - 48 + delayLen) % delayLen
                            stereo[i * 2 + 1] = ((mixed[i] * 0.65f + delayBuf[di] * 0.35f).toInt().coerceIn(-32768, 32767)).toShort()
                        }
                        writeBuf = ByteArray(stereo.size * 2)
                        java.nio.ByteBuffer.wrap(writeBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(stereo)
                        writeLen = writeBuf.size
                    } else {
                        writeBuf = ByteArray(mixed.size * 2)
                        java.nio.ByteBuffer.wrap(writeBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mixed)
                        writeLen = writeBuf.size
                    }
                    synchronized(sess) { out.write(writeBuf, 0, writeLen) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "mix loop", e)
            } finally {
                try { intAr.stop() } catch (_: Exception) {}
                try { intAr.release() } catch (_: Exception) {}
            }
        }.also { it.start() }
    }

    private fun startMicFile(cfg: RecorderConfig) {
        val useWav = cfg.audioFormat == RecorderConfig.FMT_WAV || cfg.audioFormat == RecorderConfig.FMT_MP3 || effectMode != EFFECT_STUDIO
        if (useWav) {
            val f = File(outDir("audio"), "mic_${System.currentTimeMillis()}.wav")
            outputPath = f.absolutePath
            val rate = cfg.sampleRate
            val wantFx = forceStereo || effectMode != EFFECT_STUDIO
            val source = when {
                whisperMode -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                else -> SystemAudioEffects.getAudioSourceForMode(filterMode)
            }

            // Coba stereo dulu bila perlu FX; fallback mono (banyak HP hanya mono)
            var outChannels = if (wantFx) 2 else 1
            var channelConfig = if (wantFx) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            var minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) {
                channelConfig = AudioFormat.CHANNEL_IN_MONO
                outChannels = if (wantFx) 2 else 1 // tetap bisa upmix ke stereo di loop
                minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            }
            val bufSize = (if (minBuf > 0) minBuf else rate * 2).coerceAtLeast(rate) * 2

            var ar: AudioRecord? = null
            try {
                ar = AudioRecord(source, rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    ar.release()
                    ar = null
                }
            } catch (_: Exception) {
                try { ar?.release() } catch (_: Exception) {}
                ar = null
            }
            if (ar == null) {
                channelConfig = AudioFormat.CHANNEL_IN_MONO
                outChannels = if (wantFx) 2 else 1
                val mb = AudioRecord.getMinBufferSize(rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(rate) * 2
                ar = AudioRecord(MediaRecorder.AudioSource.MIC, rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT, mb)
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    ar.release()
                    throw IllegalStateException("Mic AudioRecord gagal")
                }
            }
            attachSystemFx(ar)
            startWavSession(ar, f, rate, outChannels, bufSize)
        } else {
            // MediaRecorder AAC — urutan & error handling ala MiCode/SoundRecorder
            outputPath = File(outDir("audio"), "mic_${System.currentTimeMillis()}.m4a").absolutePath
            @Suppress("DEPRECATION")
            val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            mediaRecorder = mr
            try {
                mr.setAudioSource(
                    if (whisperMode) MediaRecorder.AudioSource.VOICE_RECOGNITION
                    else MediaRecorder.AudioSource.MIC
                )
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(cfg.audioBitrate().coerceAtLeast(64_000))
                // 44100 high quality, 22050 hemat (seperti open-source)
                val rate = if (cfg.sampleRate >= 44100) 44100 else 22050
                mr.setAudioSamplingRate(rate)
                val ch = if (forceStereo || (continueUri != null && continueChannels > 1)) 2 else 1
                mr.setAudioChannels(ch.coerceIn(1, 2))
                mr.setOutputFile(outputPath)
                mr.setOnErrorListener(this)
                try {
                    mr.prepare()
                } catch (e: IOException) {
                    try { mr.reset() } catch (_: Exception) {}
                    try { mr.release() } catch (_: Exception) {}
                    mediaRecorder = null
                    throw IllegalStateException("prepare gagal: ${e.message}", e)
                }
                try {
                    mr.start()
                } catch (e: RuntimeException) {
                    try { mr.reset() } catch (_: Exception) {}
                    try { mr.release() } catch (_: Exception) {}
                    mediaRecorder = null
                    val msg = if (isInCall()) "sedang dalam panggilan" else (e.message ?: "start gagal")
                    throw IllegalStateException("start gagal: $msg", e)
                }
            } catch (e: Exception) {
                mediaRecorder = null
                throw e
            }
        }
    }

    /** Bersihkan semua perekam bila start gagal di tengah jalan. */
    private fun abortRecorders() {
        recording = false
        releaseSystemFx()
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
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            }
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun stopAll() {
        stoppedDurMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0L
        recording = false
        releaseSystemFx()
        // Tunggu minimal ~400ms agar MediaRecorder sempat menulis track
        val elapsed = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0L
        if (elapsed < 400L) {
            try { Thread.sleep(400L - elapsed) } catch (_: Exception) {}
        }
        for (sess in wavSessions) {
            try { sess.thread?.join(800) } catch (_: Exception) {}
            try { sess.record.stop() } catch (_: Exception) {}
            try { sess.record.release() } catch (_: Exception) {}
            try { sess.out.close() } catch (_: Exception) {}
            try { WavWriter.finalize(sess.file) } catch (_: Exception) {}
        }
        wavSessions.clear()
        // Urutan wajib: stop MediaRecorder dulu, baru lepas VirtualDisplay
        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) {
            Log.e(TAG, "MediaRecorder.stop (mungkin terlalu pendek)", e)
        } catch (_: Exception) {}
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            }
            mediaProjection?.stop()
        } catch (_: Exception) {}
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
                    releaseWakeLock()
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
                    // File video terlalu kecil biasanya corrupt / tanpa track
                    if (isVideo && f.length() < 8192) {
                        throw IllegalStateException("File video terlalu kecil / tanpa track")
                    }
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
        // Rekaman layar = satu file saja; hapus sisa audio terpisah bila ada
        val extraPaths = ArrayList<String>()
        val mainIsVideo = finalPath.endsWith(".mp4", true) || outputPath.endsWith(".mp4", true)
        for (x in extraFiles) {
            try {
                if (mainIsVideo) {
                    x.delete()
                } else if (x.exists() && x.length() > 44) {
                    extraPaths.add(PublicStorage.publishFileEx(this, x, false, "audio/wav").displayPath)
                } else {
                    x.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "publish extra", e)
            }
        }
        extraFiles.clear()
        var extraNote = if (mainIsVideo || extraPaths.isEmpty()) "" else
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
        if (recording) {
            try { stopAll() } catch (_: Exception) {}
        }
        releaseWakeLock()
        super.onDestroy()
    }
}
