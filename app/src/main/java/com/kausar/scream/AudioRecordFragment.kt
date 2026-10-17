package com.kausar.scream

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class AudioRecordFragment : Fragment() {

    private lateinit var spinMode: Spinner
    private lateinit var spinFormat: Spinner
    private lateinit var spinSample: Spinner
    private lateinit var spinQuality: Spinner
    private lateinit var status: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnBookmark: Button
    private lateinit var chkStereo: CheckBox
    private lateinit var chkNoise: CheckBox
    private lateinit var chkSilence: CheckBox
    private lateinit var chkGain: CheckBox
    private lateinit var chkWhisper: CheckBox

    private val modes = arrayOf(
        "Mikrofon HP saja",
        "Internal audio",
        "Internal audio + mikrofon HP"
    )
    private val formats = arrayOf("AAC (.m4a)", "WAV (.wav)")
    private val samples = arrayOf("48000 Hz", "44100 Hz", "32000 Hz", "22050 Hz", "16000 Hz")
    private val sampleVals = intArrayOf(48000, 44100, 32000, 22050, 16000)
    private val qualities = arrayOf("Tinggi", "Sedang", "Rendah")

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchService(result.resultCode, result.data!!)
        } else {
            status.text = "Izin ditolak. Audio internal memerlukan izin rekam layar."
            status.contentDescription = status.text
        }
    }

    // Mikrofon maupun audio internal sama-sama memerlukan izin RECORD_AUDIO
    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            mulaiRekam()
        } else {
            status.text = "Izin mikrofon ditolak. Aktifkan lewat Pengaturan > Aplikasi > Izin."
            status.contentDescription = status.text
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val ctx = context ?: return
            val msg = i?.getStringExtra(RecordingService.EXTRA_STATUS) ?: return
            val src = i.getStringExtra(RecordingService.EXTRA_SOURCE) ?: RecordingService.SOURCE_AUDIO
            if (src != RecordingService.SOURCE_AUDIO) return
            val path = i.getStringExtra(RecordingService.EXTRA_PATH) ?: ""
            val uri = i.getStringExtra(RecordingService.EXTRA_URI) ?: ""
            val note = i.getStringExtra(RecordingService.EXTRA_NOTE) ?: ""
            val dur = i.getLongExtra(RecordingService.EXTRA_DURATION_MS, 0L)

            if (msg.contains("selesai", true)) {
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                btnBookmark.isEnabled = false
                setSpinnersEnabled(true)
                val durText = formatDuration(dur)
                status.text = "Rekaman dihentikan.\nDurasi: $durText" +
                    if (path.isNotEmpty()) "\n\nDisimpan di Musik:\n$path" else ""
                status.contentDescription = status.text
                PlayerDialog.showAfterRecording(ctx, uri, path, durText, note)
            } else if (msg.contains("Gagal", true)) {
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                btnBookmark.isEnabled = false
                setSpinnersEnabled(true)
                status.text = msg
                status.contentDescription = msg
            } else {
                status.text = if (path.isNotEmpty()) "$msg\n$path" else msg
                status.contentDescription = status.text
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "$m menit $s detik" else "$s detik"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
        }

        root.addView(title(ctx, "Perekam suara"))
        root.addView(hint(ctx, "File disimpan ke folder Musik/KausarRecorder.\nFormat: AAC atau WAV. Internal audio = Android 10+.\nInternal + mikrofon digabung jadi satu file WAV. Fitur lanjutan (efek/noise/gain) otomatis memakai WAV."))

        root.addView(label(ctx, "Sumber audio"))
        spinMode = spinner(ctx, modes)
        spinMode.contentDescription = "Pilih sumber audio"
        root.addView(spinMode)

        root.addView(label(ctx, "Format file"))
        spinFormat = spinner(ctx, formats)
        spinFormat.contentDescription = "Pilih format file rekaman"
        root.addView(spinFormat)

        root.addView(label(ctx, "Sample rate"))
        spinSample = spinner(ctx, samples)
        spinSample.setSelection(1)
        spinSample.contentDescription = "Pilih sample rate"
        root.addView(spinSample)

        root.addView(label(ctx, "Kualitas audio"))
        spinQuality = spinner(ctx, qualities)
        spinQuality.setSelection(1)
        spinQuality.contentDescription = "Pilih kualitas audio"
        root.addView(spinQuality)

        root.addView(label(ctx, "Fitur Raksasa"))
        chkStereo = checkBox(ctx, "Paksa Stereo (meski mic mono jadi stereo)", true)
        root.addView(chkStereo)
        chkNoise = checkBox(ctx, "Mode Noise (noise lembut seperti Windows, suara tetap jernih)", false)
        root.addView(chkNoise)
        chkSilence = checkBox(ctx, "Lewati Keheningan Cerdas (skip silence)", false)
        root.addView(chkSilence)
        chkGain = checkBox(ctx, "Penguat Dinamis (AGC / boost suara pelan)", false)
        root.addView(chkGain)
        chkWhisper = checkBox(ctx, "Mode Bisik (sensitivitas tinggi untuk suara pelan)", false)
        root.addView(chkWhisper)

        btnStart = Button(ctx).apply {
            text = "Mulai rekam suara"
            contentDescription = "Tombol mulai rekam suara"
            setOnClickListener { mulaiRekam() }
        }
        btnStop = Button(ctx).apply {
            text = "Stop"
            isEnabled = false
            contentDescription = "Tombol hentikan rekaman"
            setOnClickListener {
                val i = Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
                try { ctx.startService(i) } catch (_: Exception) {}
            }
        }
        btnBookmark = Button(ctx).apply {
            text = "Tandai Bookmark"
            isEnabled = false
            contentDescription = "Tombol tambah bookmark timestamp saat rekaman berjalan"
            setOnClickListener {
                val i = Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_BOOKMARK)
                try { ctx.startService(i) } catch (_: Exception) {}
                status.text = "Bookmark ditambahkan pada posisi saat ini."
                status.contentDescription = status.text
            }
        }
        root.addView(btnStart)
        root.addView(btnStop)
        root.addView(btnBookmark)

        status = TextView(ctx).apply {
            text = "Siap."
            setTextColor(Color.parseColor("#374151"))
            setPadding(0, dp(12), 0, 0)
            contentDescription = "Status rekaman: Siap"
        }
        root.addView(status)

        return ScrollView(ctx).apply { addView(root); setBackgroundColor(Color.WHITE) }
    }

    override fun onStart() {
        super.onStart()
        if (!RecordingService.isRunning) {
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            btnBookmark.isEnabled = false
            setSpinnersEnabled(true)
        }
        val f = IntentFilter(RecordingService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(statusReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(statusReceiver, f)
        }
    }

    override fun onStop() {
        try { requireContext().unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    private fun mulaiRekam() {
        if (RecordingService.isRunning) {
            status.text = "Masih ada rekaman yang berjalan. Hentikan dulu sebelum memulai yang baru."
            status.contentDescription = status.text
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            status.text = "Izin mikrofon diperlukan untuk merekam. Mohon izinkan."
            status.contentDescription = status.text
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val mode = spinMode.selectedItemPosition
        val needInternal = mode == RecorderConfig.AUDIO_INTERNAL || mode == RecorderConfig.AUDIO_INTERNAL_MIC
        if (needInternal) {
            if (Build.VERSION.SDK_INT < 29) {
                status.text = "Audio internal hanya di Android 10+"
                status.contentDescription = status.text
                return
            }
            val mgr = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            status.text = "Izin rekam layar diperlukan untuk audio internal..."
            status.contentDescription = status.text
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } else {
            launchService(0, null)
        }
    }

    private fun launchService(code: Int, data: Intent?) {
        val ctx = requireContext()
        val fmt = if (spinFormat.selectedItemPosition == 1) RecorderConfig.FMT_WAV else RecorderConfig.FMT_AAC
        val i = Intent(ctx, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            // Hanya kirim token MediaProjection yang valid. Intent kosong membuat service
            // mengira ada proyeksi layar dan crash di Android 14+.
            if (code == Activity.RESULT_OK && data != null) {
                putExtra(RecordingService.EXTRA_RESULT_CODE, code)
                putExtra(RecordingService.EXTRA_RESULT_DATA, data)
            }
            putExtra(RecordingService.EXTRA_IS_SCREEN, false)
            putExtra(RecordingService.EXTRA_MODE, spinMode.selectedItemPosition)
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, sampleVals[spinSample.selectedItemPosition])
            putExtra(RecordingService.EXTRA_AUDIO_QUALITY, spinQuality.selectedItemPosition)
            putExtra(RecordingService.EXTRA_VIDEO_QUALITY, 1)
            putExtra(RecordingService.EXTRA_AUDIO_FORMAT, fmt)
            putExtra(RecordingService.EXTRA_FORCE_STEREO, chkStereo.isChecked)
            putExtra(RecordingService.EXTRA_NOISE, chkNoise.isChecked)
            putExtra(RecordingService.EXTRA_SKIP_SILENCE, chkSilence.isChecked)
            putExtra(RecordingService.EXTRA_DYNAMIC_GAIN, chkGain.isChecked)
            putExtra(RecordingService.EXTRA_WHISPER, chkWhisper.isChecked)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            status.text = "Gagal memulai rekaman: ${e.message ?: e.javaClass.simpleName}"
            status.contentDescription = status.text
            return
        }
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        btnBookmark.isEnabled = true
        setSpinnersEnabled(false)
        status.text = "Rekaman suara berjalan..."
        status.contentDescription = status.text
    }

    private fun setSpinnersEnabled(en: Boolean) {
        spinMode.isEnabled = en
        spinFormat.isEnabled = en
        spinSample.isEnabled = en
        spinQuality.isEnabled = en
        chkStereo.isEnabled = en
        chkNoise.isEnabled = en
        chkSilence.isEnabled = en
        chkGain.isEnabled = en
        chkWhisper.isEnabled = en
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun title(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#111827")); setPadding(0, 0, 0, dp(6))
        contentDescription = t
    }

    private fun hint(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 12f; setTextColor(Color.parseColor("#6B7280")); setPadding(0, 0, 0, dp(10))
        contentDescription = t
    }

    private fun label(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#111827")); setPadding(0, dp(10), 0, dp(4))
        contentDescription = t
    }

    private fun spinner(ctx: Context, items: Array<String>) = Spinner(ctx).apply {
        adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun checkBox(ctx: Context, label: String, checked: Boolean) = CheckBox(ctx).apply {
        text = label
        isChecked = checked
        contentDescription = label
        setTextColor(Color.parseColor("#111827"))
        setPadding(0, dp(4), 0, dp(4))
    }
}
