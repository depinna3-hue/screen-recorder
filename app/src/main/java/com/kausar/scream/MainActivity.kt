package com.kausar.scream

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnBookmark: Button
    private lateinit var spinRecType: Spinner
    private lateinit var spinMic: Spinner
    private lateinit var spinFormat: Spinner
    private lateinit var spinSample: Spinner
    private lateinit var spinEffect: Spinner
    private lateinit var spinFilter: Spinner
    private lateinit var seekGain: SeekBar
    private lateinit var seekNoise: SeekBar
    private lateinit var txtGain: TextView
    private lateinit var txtNoise: TextView
    private lateinit var chkStereo: CheckBox
    private lateinit var chkNoise: CheckBox
    private lateinit var chkSilence: CheckBox
    private lateinit var chkAgc: CheckBox
    private lateinit var listContainer: LinearLayout

    private val sampleVals = intArrayOf(48000, 44100, 32000, 22050, 16000)
    private val sampleLabels = arrayOf("48000 Hz", "44100 Hz", "32000 Hz", "22050 Hz", "16000 Hz")

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            launchService(result.resultCode, result.data!!, spinRecType.selectedItemPosition == 0)
        } else {
            setStatus(getString(R.string.status_failed) + ": " + getString(R.string.status_projection_denied))
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val msg = i?.getStringExtra(RecordingService.EXTRA_STATUS) ?: return
            val path = i.getStringExtra(RecordingService.EXTRA_PATH) ?: ""
            val uri = i.getStringExtra(RecordingService.EXTRA_URI) ?: ""
            val note = i.getStringExtra(RecordingService.EXTRA_NOTE) ?: ""
            val dur = i.getLongExtra(RecordingService.EXTRA_DURATION_MS, 0L)

            if (msg.contains("selesai", true) || msg.contains("stopped", true) ||
                msg.contains(getString(R.string.status_stopped), true)
            ) {
                setRecordingUi(false)
                val durText = formatDuration(dur)
                setStatus("${getString(R.string.status_stopped)}\n${getString(R.string.duration)}: $durText" +
                    if (path.isNotEmpty()) "\n${getString(R.string.saved_to)}:\n$path" else "")
                if (!isFinishing && !(android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed)) {
                    PlayerDialog.showAfterRecording(this@MainActivity, uri, path, durText, note)
                }
                refreshList()
            } else if (msg.contains("Gagal", true) || msg.contains("Failed", true) ||
                msg.contains(getString(R.string.status_failed), true)
            ) {
                setRecordingUi(false)
                setStatus(msg)
            } else {
                setStatus(if (path.isNotEmpty()) "$msg\n$path" else msg)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
            PublicStorage.ensureFolders(this)
            requestAllPermissions()
            refreshList()
            // Toast developer — hardcoded, bukan string resource
            Toast.makeText(this, "Developer by Alkausar", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            val tv = TextView(this)
            tv.text = "Error:\n${t.message}"
            tv.setPadding(32, 32, 32, 32)
            setContentView(tv)
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F0F2F5")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(20))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#075E54"))
            setPadding(dp(16), dp(16), dp(16), dp(14))
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.header_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.header_hint)
            textSize = 13f
            setTextColor(Color.parseColor("#D1F4DE"))
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(6) })

        // Petunjuk langsung setelah nama/header
        root.addView(Button(this).apply {
            text = getString(R.string.btn_help)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.help_title))
                    .setMessage(getString(R.string.help_body))
                    .setPositiveButton(getString(R.string.player_ok), null)
                    .show()
            }
        })

        // Spinner 1: jenis rekaman
        root.addView(section(getString(R.string.section_rec_type)))
        spinRecType = spinner(arrayOf(
            getString(R.string.rec_type_screen),
            getString(R.string.rec_type_audio)
        ))
        root.addView(spinRecType)

        // Spinner 2: sumber mikrofon/audio
        root.addView(section(getString(R.string.section_mic_source)))
        spinMic = spinner(arrayOf(
            getString(R.string.mode_mic),
            getString(R.string.mode_internal),
            getString(R.string.mode_internal_mic),
            getString(R.string.mode_screen)
        ))
        root.addView(spinMic)

        root.addView(section(getString(R.string.section_format)))
        spinFormat = spinner(arrayOf(getString(R.string.format_aac), getString(R.string.format_wav)))
        root.addView(spinFormat)
        root.addView(label(getString(R.string.label_sample)))
        spinSample = spinner(sampleLabels)
        spinSample.setSelection(1)
        root.addView(spinSample)

        root.addView(section(getString(R.string.section_effects)))
        spinEffect = spinner(arrayOf(
            getString(R.string.effect_studio),
            getString(R.string.effect_flanger),
            getString(R.string.effect_echo),
            getString(R.string.effect_reverb)
        ))
        root.addView(spinEffect)

        root.addView(section(getString(R.string.section_filter)))
        spinFilter = spinner(arrayOf(
            getString(R.string.filter_original),
            getString(R.string.filter_voice),
            getString(R.string.filter_quiet),
            getString(R.string.filter_music),
            getString(R.string.filter_outdoor)
        ))
        root.addView(spinFilter)

        root.addView(label(getString(R.string.label_mic_gain)))
        txtGain = TextView(this).apply {
            text = "100%"
            setTextColor(Color.parseColor("#374151"))
            textSize = 12f
        }
        root.addView(txtGain)
        seekGain = SeekBar(this).apply {
            max = 200
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { txtGain.text = "$p%" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(seekGain)

        root.addView(label(getString(R.string.label_noise)))
        txtNoise = TextView(this).apply {
            text = "30%"
            setTextColor(Color.parseColor("#374151"))
            textSize = 12f
        }
        root.addView(txtNoise)
        seekNoise = SeekBar(this).apply {
            max = 100
            progress = 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { txtNoise.text = "$p%" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(seekNoise)

        chkStereo = checkBox(getString(R.string.chk_stereo), true)
        root.addView(chkStereo)
        chkNoise = checkBox(getString(R.string.chk_noise), false)
        root.addView(chkNoise)
        chkSilence = checkBox(getString(R.string.chk_silence), false)
        root.addView(chkSilence)
        chkAgc = checkBox(getString(R.string.chk_gain_auto), false)
        root.addView(chkAgc)

        root.addView(section(getString(R.string.section_controls)))
        btnStart = Button(this).apply {
            text = getString(R.string.btn_start)
            setOnClickListener { mulaiRekam() }
        }
        btnStop = Button(this).apply {
            text = getString(R.string.btn_stop)
            isEnabled = false
            setOnClickListener {
                startService(Intent(this@MainActivity, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_STOP))
            }
        }
        btnBookmark = Button(this).apply {
            text = getString(R.string.btn_bookmark)
            isEnabled = false
            setOnClickListener {
                startService(Intent(this@MainActivity, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_BOOKMARK))
            }
        }
        root.addView(btnStart)
        root.addView(btnStop)
        root.addView(btnBookmark)

        root.addView(Button(this).apply {
            text = getString(R.string.btn_settings)
            setOnClickListener { showSettings() }
        })
        status = TextView(this).apply {
            text = getString(R.string.status_ready)
            setTextColor(Color.parseColor("#374151"))
            setPadding(0, dp(10), 0, dp(8))
        }
        root.addView(status)

        root.addView(section(getString(R.string.section_list)))
        root.addView(Button(this).apply {
            text = getString(R.string.btn_refresh_list)
            setOnClickListener { refreshList() }
        })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun mulaiRekam() {
        if (RecordingService.isRunning) {
            setStatus(getString(R.string.status_already_running))
            return
        }
        val isScreen = spinRecType.selectedItemPosition == 0
        val micPos = spinMic.selectedItemPosition
        // mic: 0=mic only, 1=internal, 2=internal+mic, 3=screen+mic (legacy label)
        val needInternal = micPos == 1 || micPos == 2
        if (isScreen || needInternal) {
            if (needInternal && Build.VERSION.SDK_INT < 29) {
                setStatus(getString(R.string.status_internal_api))
                return
            }
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            setStatus(getString(R.string.status_need_projection))
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } else {
            launchService(0, Intent(), false)
        }
    }

    private fun launchService(code: Int, data: Intent, isScreen: Boolean) {
        val micPos = spinMic.selectedItemPosition
        val cfgMode = when {
            // Rekaman layar: selalu satu file MP4 (video + audio menyatu)
            isScreen -> RecorderConfig.SCREEN_MIC
            else -> when (micPos) {
                1 -> RecorderConfig.AUDIO_INTERNAL
                2 -> RecorderConfig.AUDIO_INTERNAL_MIC
                else -> RecorderConfig.AUDIO_MIC
            }
        }
        val fmt = if (spinFormat.selectedItemPosition == 1) RecorderConfig.FMT_WAV else RecorderConfig.FMT_AAC
        val i = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RESULT_CODE, code)
            putExtra(RecordingService.EXTRA_RESULT_DATA, data)
            putExtra(RecordingService.EXTRA_IS_SCREEN, isScreen)
            putExtra(RecordingService.EXTRA_MODE, cfgMode)
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, sampleVals[spinSample.selectedItemPosition])
            putExtra(RecordingService.EXTRA_AUDIO_QUALITY, 1)
            putExtra(RecordingService.EXTRA_VIDEO_QUALITY, 1)
            putExtra(RecordingService.EXTRA_AUDIO_FORMAT, fmt)
            putExtra(RecordingService.EXTRA_FORCE_STEREO, chkStereo.isChecked)
            putExtra(RecordingService.EXTRA_NOISE, chkNoise.isChecked)
            putExtra(RecordingService.EXTRA_SKIP_SILENCE, chkSilence.isChecked)
            putExtra(RecordingService.EXTRA_DYNAMIC_GAIN, chkAgc.isChecked)
            putExtra(RecordingService.EXTRA_WHISPER, false)
            putExtra(RecordingService.EXTRA_MIC_GAIN, seekGain.progress / 100f)
            putExtra(RecordingService.EXTRA_NOISE_LEVEL, seekNoise.progress / 100f)
            putExtra(RecordingService.EXTRA_EFFECT, spinEffect.selectedItemPosition)
            putExtra(RecordingService.EXTRA_FILTER_MODE, spinFilter.selectedItemPosition)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        setRecordingUi(true)
        setStatus(getString(R.string.status_recording))
    }

    private fun setRecordingUi(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
        btnBookmark.isEnabled = running
        spinRecType.isEnabled = !running
        spinMic.isEnabled = !running
        spinFormat.isEnabled = !running
        spinSample.isEnabled = !running
        spinEffect.isEnabled = !running
        spinFilter.isEnabled = !running
        seekGain.isEnabled = !running
        seekNoise.isEnabled = !running
        chkStereo.isEnabled = !running
        chkNoise.isEnabled = !running
        chkSilence.isEnabled = !running
        chkAgc.isEnabled = !running
    }

    private fun setStatus(t: String) { status.text = t }

    private fun refreshList() {
        listContainer.removeAllViews()
        try {
            val items = (RecordingStore.list(this, false) + RecordingStore.list(this, true))
                .sortedByDescending { it.dateMs }
            if (items.isEmpty()) {
                listContainer.addView(TextView(this).apply {
                    text = getString(R.string.no_recordings)
                    setTextColor(Color.parseColor("#6B7280"))
                    setPadding(0, dp(8), 0, 0)
                })
            } else {
                for (item in items.take(20)) {
                    val uriStr = item.uri.toString()
                    listContainer.addView(TextView(this).apply {
                        text = "• ${item.name}"
                        setTextColor(Color.parseColor("#111827"))
                        textSize = 13f
                        setPadding(0, dp(6), 0, dp(6))
                        setOnClickListener {
                            PlayerDialog.showAfterRecording(this@MainActivity, uriStr, item.name, "", "")
                        }
                    })
                }
            }
        } catch (_: Exception) {
            listContainer.addView(TextView(this).apply {
                text = getString(R.string.no_recordings)
                setTextColor(Color.parseColor("#6B7280"))
            })
        }
    }

    private fun showSettings() {
        val codes = LanguageManager.LANGUAGES.map { it.code }.toTypedArray()
        val names = LanguageManager.LANGUAGES.map { it.name }.toTypedArray()
        val current = LanguageManager.getSavedCode(this)
        var selected = codes.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_title))
            .setSingleChoiceItems(names, selected) { _, which -> selected = which }
            .setPositiveButton(getString(R.string.settings_apply)) { _, _ ->
                LanguageManager.saveCode(this, codes[selected])
                Toast.makeText(this, getString(R.string.lang_changed), Toast.LENGTH_LONG).show()
                recreate()
            }
            .setNegativeButton(getString(R.string.settings_close), null)
            .show()
    }

    private fun requestAllPermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 991)
        RequestPermission.minta(this)
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter(RecordingService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(statusReceiver, f)
    }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "$m m $s s" else "$s s"
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#075E54")); setPadding(0, dp(14), 0, dp(6))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#111827")); setPadding(0, dp(8), 0, dp(2))
    }

    private fun spinner(items: Array<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun checkBox(label: String, checked: Boolean) = CheckBox(this).apply {
        text = label; isChecked = checked
        setTextColor(Color.parseColor("#111827")); setPadding(0, dp(2), 0, dp(2))
    }
}
