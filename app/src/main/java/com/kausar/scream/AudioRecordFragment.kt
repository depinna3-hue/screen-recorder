package com.kausar.scream

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
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
import java.io.File
import java.util.concurrent.Executors

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
    private val formats = arrayOf("AAC (.m4a)", "MP3 (konversi setelah stop)", "WAV (.wav)")
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
            val path = i.getStringExtra(RecordingService.EXTRA_PATH) ?: ""
            val dur = i.getLongExtra(RecordingService.EXTRA_DURATION_MS, 0L)
            val wantMp3 = i.getBooleanExtra(RecordingService.EXTRA_WANT_MP3, false)

            if (msg.contains("selesai", true)) {
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                setSpinnersEnabled(true)
                val durText = formatDuration(dur)
                status.text = "Rekaman dihentikan.\nDurasi: $durText" +
                    if (path.isNotEmpty()) "\n\nFile sementara:\n$path" else ""

                AlertDialog.Builder(requireContext())
                    .setTitle("Rekaman dihentikan")
                    .setMessage("Durasi: $durText")
                    .setPositiveButton("OK") { _, _ ->
                        if (wantMp3 && path.isNotEmpty() && path.endsWith(".wav", true)) {
                            convertToMp3(File(path), durText)
                        }
                    }
                    .setCancelable(false)
                    .show()
            } else if (msg.contains("Gagal", true)) {
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                setSpinnersEnabled(true)
                status.text = msg
            } else {
                status.text = if (path.isNotEmpty()) "$msg\n\n$path" else msg
            }
        }
    }

    private fun convertToMp3(wav: File, durText: String) {
        val ctx = requireContext()
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(ctx).apply {
            setTitle("Mengonversi ke MP3")
            setMessage("Rekaman dihentikan. Durasi: $durText\n\nMengonversi… 0%")
            setCancelable(false)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            show()
        }

        Executors.newSingleThreadExecutor().execute {
            val result = Mp3Converter.convertWavToCompressed(wav, preferMp3 = true) { p ->
                if (activity != null) {
                    requireActivity().runOnUiThread {
                        progress.progress = p
                        progress.setMessage(
                            "Rekaman dihentikan. Durasi: $durText\n\nMengonversi… $p%"
                        )
                    }
                }
            }
            if (activity != null) {
                requireActivity().runOnUiThread {
                    progress.dismiss()
                    if (result.ok && result.outFile != null) {
                        status.text = "Rekaman dihentikan.\nDurasi: $durText\n\n${result.message}\n\nFile:\n${result.outFile.absolutePath}"
                        AlertDialog.Builder(ctx)
                            .setTitle("Konversi selesai")
                            .setMessage("Durasi: $durText\n\n${result.message}\n\n${result.outFile.absolutePath}")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        status.text = "Rekaman dihentikan.\nDurasi: $durText\n\n${result.message}\nFile WAV: ${wav.absolutePath}"
                        Toast.makeText(ctx, result.message, Toast.LENGTH_LONG).show()
                    }
                }
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
        }

        root.addView(title(ctx, "Perekam suara"))
        root.addView(hint(ctx, "Pilih MP3 → direkam dulu, lalu otomatis dikonversi setelah stop (progres %). Internal audio = Android 10+."))

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
            text = "Siap. Pilih sumber, format, sample rate, dan kualitas."
            setTextColor(Color.parseColor("#374151"))
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(status)

        return ScrollView(ctx).apply { addView(root) }
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
        val mode = spinMode.selectedItemPosition
        val needInternal = mode == RecorderConfig.AUDIO_INTERNAL || mode == RecorderConfig.AUDIO_INTERNAL_MIC
        if (needInternal) {
            if (Build.VERSION.SDK_INT < 29) {
                status.text = "Audio internal hanya di Android 10+"
                return
            }
            val mgr = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            status.text = "Izin rekam layar diperlukan untuk menangkap audio internal..."
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } else {
            launchService(0, Intent())
        }
    }

    private fun launchService(code: Int, data: Intent) {
        val ctx = requireContext()
        val fmt = when (spinFormat.selectedItemPosition) {
            1 -> RecorderConfig.FMT_MP3
            2 -> RecorderConfig.FMT_WAV
            else -> RecorderConfig.FMT_AAC
        }
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