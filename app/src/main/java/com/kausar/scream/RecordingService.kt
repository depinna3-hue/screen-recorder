package com.kausar.scream

import android.app.Activity
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

class ScreenRecordFragment : Fragment() {

    private lateinit var spinMode: Spinner
    private lateinit var spinSample: Spinner
    private lateinit var spinQuality: Spinner
    private lateinit var spinVideo: Spinner
    private lateinit var status: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val modes = arrayOf(
        "Mikrofon + layar",
        "Internal audio + layar",
        "Internal audio saja"
    )
    private val samples = arrayOf("48000 Hz", "44100 Hz", "32000 Hz", "22050 Hz", "16000 Hz")
    private val sampleVals = intArrayOf(48000, 44100, 32000, 22050, 16000)
    private val qualities = arrayOf("Tinggi", "Sedang", "Rendah")
    private val videos = arrayOf("480p", "720p HD", "1080p Full HD")

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startService(result.resultCode, result.data!!)
        } else {
            status.text = "Izin rekam layar ditolak."
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val msg = i?.getStringExtra(RecordingService.EXTRA_STATUS) ?: return
            val path = i.getStringExtra(RecordingService.EXTRA_PATH) ?: ""
            status.text = if (path.isNotEmpty()) "$msg\n\nFile:\n$path" else msg
            if (msg.contains("selesai", true) || msg.contains("Gagal", true)) {
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                setSpinnersEnabled(true)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        root.addView(title(ctx, "Perekam layar"))
        root.addView(hint(ctx, "Format video: MP4. Audio internal butuh Android 10+ dan izin rekam layar."))

        root.addView(label(ctx, "Mode rekam"))
        spinMode = spinner(ctx, modes)
        root.addView(spinMode)

        root.addView(label(ctx, "Sample rate audio"))
        spinSample = spinner(ctx, samples)
        spinSample.setSelection(1)
        root.addView(spinSample)

        root.addView(label(ctx, "Kualitas audio"))
        spinQuality = spinner(ctx, qualities)
        spinQuality.setSelection(1)
        root.addView(spinQuality)

        root.addView(label(ctx, "Kualitas video (HD)"))
        spinVideo = spinner(ctx, videos)
        spinVideo.setSelection(1)
        root.addView(spinVideo)

        btnStart = Button(ctx).apply {
            text = "Mulai rekam layar"
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
            text = "Siap. Pilih mode, sample rate, kualitas audio & video."
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
        val needProjection = true // layar / internal selalu butuh projection kecuali kita hanya mic — di halaman ini selalu projection
        if (mode != RecorderConfig.SCREEN_MIC && Build.VERSION.SDK_INT < 29) {
            status.text = "Mode internal audio hanya di Android 10 (API 29) ke atas."
            return
        }
        val mgr = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
        status.text = "Menunggu izin rekam layar..."
    }

    private fun startService(code: Int, data: Intent) {
        val ctx = requireContext()
        val i = Intent(ctx, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RESULT_CODE, code)
            putExtra(RecordingService.EXTRA_RESULT_DATA, data)
            putExtra(RecordingService.EXTRA_IS_SCREEN, true)
            putExtra(RecordingService.EXTRA_MODE, spinMode.selectedItemPosition)
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, sampleVals[spinSample.selectedItemPosition])
            putExtra(RecordingService.EXTRA_AUDIO_QUALITY, spinQuality.selectedItemPosition)
            putExtra(RecordingService.EXTRA_VIDEO_QUALITY, spinVideo.selectedItemPosition)
            putExtra(RecordingService.EXTRA_AUDIO_FORMAT, RecorderConfig.FMT_AAC)
        }
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        setSpinnersEnabled(false)
        status.text = "Rekaman layar berjalan..."
    }

    private fun setSpinnersEnabled(en: Boolean) {
        spinMode.isEnabled = en
        spinSample.isEnabled = en
        spinQuality.isEnabled = en
        spinVideo.isEnabled = en
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