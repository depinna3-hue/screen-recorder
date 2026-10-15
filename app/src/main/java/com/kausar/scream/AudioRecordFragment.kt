package com.kausar.scream

import android.app.Activity
import android.app.AlertDialog
import android.content.*
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
import androidx.fragment.app.Fragment

class AudioRecordFragment : Fragment() {

    private lateinit var spinMode: Spinner
    private lateinit var spinFormat: Spinner
    private lateinit var spinSample: Spinner
    private lateinit var spinQuality: Spinner
    private lateinit var status: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val modes = arrayOf(
        "Mikrofon HP saja",
        "Internal audio",
        "Internal audio + mikrofon HP"
    )
    // Jujur: tidak ada encoder MP3 bawaan Android
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
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val msg = i?.getStringExtra(RecordingService.EXTRA_STATUS) ?: return
            // Hanya tanggapi rekaman suara dari tab ini (abaikan siaran layar / lanjutkan rekaman)
            val src = i.getStringExtra(RecordingService.EXTRA_SOURCE) ?: RecordingService.SOURCE_AUDIO
            if (src != RecordingService.SOURCE_AUDIO) return
            val path = i.getStringExtra(RecordingService.EXTRA_PATH) ?: ""
            val uri = i.getStringExtra(RecordingService.EXTRA_URI) ?: ""
            val dur = i.getLongExtra(RecordingService.EXTRA_DURATION_MS, 0L)

            if (msg.contains("selesai", true)) {
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                setSpinnersEnabled(true)
                val durText = formatDuration(dur)
                status.text = "Rekaman dihentikan.\nDurasi: $durText" +
                    if (path.isNotEmpty()) "\n\nDisimpan di Musik:\n$path" else ""
                // Tampilkan pemutar agar hasil rekaman bisa langsung didengar
                PlayerDialog.showAfterRecording(requireContext(), uri, path, durText)
            } else if (msg.contains("Gagal", true)) {
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                setSpinnersEnabled(true)
                status.text = msg
            } else {
                status.text = if (path.isNotEmpty()) "$msg\n$path" else msg
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
        root.addView(hint(ctx, "File disimpan ke folder Musik/KausarRecorder (bukan data app).\nFormat nyata: AAC atau WAV. Internal audio = Android 10+."))

        root.addView(label(ctx, "Sumber audio"))
        spinMode = spinner(ctx, modes)
        root.addView(spinMode)

        root.addView(label(ctx, "Format file"))
        spinFormat = spinner(ctx, formats)
        root.addView(spinFormat)

        root.addView(label(ctx, "Sample rate"))
        spinSample = spinner(ctx, samples)
        spinSample.setSelection(1)
        root.addView(spinSample)

        root.addView(label(ctx, "Kualitas audio"))
        spinQuality = spinner(ctx, qualities)
        spinQuality.setSelection(1)
        root.addView(spinQuality)

        btnStart = Button(ctx).apply {
            text = "Mulai rekam suara"
            setOnClickListener { mulaiRekam() }
        }
        btnStop = Button(ctx).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener {
                val i = Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
                ctx.startService(i)
            }
        }
        root.addView(btnStart)
        root.addView(btnStop)

        status = TextView(ctx).apply {
            text = "Siap."
            setTextColor(Color.parseColor("#374151"))
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(status)

        return ScrollView(ctx).apply { addView(root); setBackgroundColor(Color.WHITE) }
    }

    override fun onStart() {
        super.onStart()
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
            return
        }
        val mode = spinMode.selectedItemPosition
        val needInternal = mode == RecorderConfig.AUDIO_INTERNAL || mode == RecorderConfig.AUDIO_INTERNAL_MIC
        if (needInternal) {
            if (Build.VERSION.SDK_INT < 29) {
                status.text = "Audio internal hanya di Android 10+"
                return
            }
            val mgr = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            status.text = "Izin rekam layar diperlukan untuk audio internal..."
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } else {
            launchService(0, Intent())
        }
    }

    private fun launchService(code: Int, data: Intent) {
        val ctx = requireContext()
        val fmt = if (spinFormat.selectedItemPosition == 1) RecorderConfig.FMT_WAV else RecorderConfig.FMT_AAC
        val i = Intent(ctx, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RESULT_CODE, code)
            putExtra(RecordingService.EXTRA_RESULT_DATA, data)
            putExtra(RecordingService.EXTRA_IS_SCREEN, false)
            putExtra(RecordingService.EXTRA_MODE, spinMode.selectedItemPosition)
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, sampleVals[spinSample.selectedItemPosition])
            putExtra(RecordingService.EXTRA_AUDIO_QUALITY, spinQuality.selectedItemPosition)
            putExtra(RecordingService.EXTRA_VIDEO_QUALITY, 1)
            putExtra(RecordingService.EXTRA_AUDIO_FORMAT, fmt)
        }
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        setSpinnersEnabled(false)
        status.text = "Rekaman suara berjalan..."
    }

    private fun setSpinnersEnabled(en: Boolean) {
        spinMode.isEnabled = en
        spinFormat.isEnabled = en
        spinSample.isEnabled = en
        spinQuality.isEnabled = en
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun title(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#111827")); setPadding(0, 0, 0, dp(6))
    }

    private fun hint(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 12f; setTextColor(Color.parseColor("#6B7280")); setPadding(0, 0, 0, dp(10))
    }

    private fun label(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#111827")); setPadding(0, dp(10), 0, dp(4))
    }

    private fun spinner(ctx: Context, items: Array<String>) = Spinner(ctx).apply {
        adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
    }
}
