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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tab "Daftar": semua rekaman suara / layar milik app ini.
 * Ketuk rekaman → Putar, Lanjutkan rekaman (khusus suara .m4a / .wav), Bagikan, Hapus.
 */
class RecordingsFragment : Fragment() {

    private class Holder(val name: TextView, val info: TextView)

    private val ui = Handler(Looper.getMainLooper())
    private val items = ArrayList<RecordingStore.Item>()
    private var showVideo = false
    @Volatile
    private var loadToken = 0

    private lateinit var chipAudio: TextView
    private lateinit var chipVideo: TextView
    private lateinit var countText: TextView
    private lateinit var emptyText: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: BaseAdapter

    // --- state "Lanjutkan rekaman" ---
    private var contDialog: AlertDialog? = null
    private var contTimer: TextView? = null
    private var contInfo: TextView? = null
    private var contItem: RecordingStore.Item? = null
    private var contStartedAt = 0L
    private var pendingContinue: RecordingStore.Item? = null
    private var receiverRegistered = false
    private var appCtx: Context? = null

    private val dateFmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale("id", "ID"))

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val item = pendingContinue
        pendingContinue = null
        if (granted && item != null) {
            continueRecording(item)
        } else if (!granted) {
            toast("Izin mikrofon diperlukan untuk melanjutkan rekaman")
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null) return
            // Hanya siaran dari "lanjutkan rekaman"
            if (i.getStringExtra(RecordingService.EXTRA_SOURCE) != RecordingService.SOURCE_CONTINUE) return
            val msg = i.getStringExtra(RecordingService.EXTRA_STATUS) ?: return
            if (!isAdded) return
            when {
                msg.contains("selesai", true) -> onContinueFinished(
                    i.getStringExtra(RecordingService.EXTRA_URI) ?: "",
                    i.getStringExtra(RecordingService.EXTRA_PATH) ?: "",
                    i.getLongExtra(RecordingService.EXTRA_DURATION_MS, 0L),
                    i.getStringExtra(RecordingService.EXTRA_NOTE) ?: ""
                )
                msg.contains("Gagal", true) -> onContinueFailed(msg)
                else -> onContinueStarted()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val t = contTimer
            if (t != null && contStartedAt > 0) {
                t.text = PlayerDialog.clock(SystemClock.elapsedRealtime() - contStartedAt)
            }
            if (contDialog != null) ui.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Didaftarkan selama fragment hidup supaya hasil "lanjutkan rekaman" tidak terlewat
        val f = IntentFilter(RecordingService.BROADCAST_STATUS)
        val app = requireContext().applicationContext
        appCtx = app
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(statusReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(statusReceiver, f)
        }
        receiverRegistered = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(title(ctx, "Daftar rekaman"))
        root.addView(hint(ctx, "Ketuk rekaman untuk memutar, melanjutkan rekaman suara, membagikan, atau menghapus."))

        chipAudio = chip(ctx, "Suara") { selectKind(false) }
        chipVideo = chip(ctx, "Layar") { selectKind(true) }
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(chipAudio, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(6)
        })
        chips.addView(chipVideo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(6)
        })
        root.addView(chips)

        countText = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(countText)

        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val holder: Holder
                val row: View
                if (convertView == null) {
                    val c = parent.context
                    val name = TextView(c).apply {
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.parseColor("#111827"))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    }
                    val info = TextView(c).apply {
                        textSize = 12f
                        setTextColor(Color.parseColor("#6B7280"))
                        setPadding(0, dp(2), 0, 0)
                    }
                    val ll = LinearLayout(c).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(4), dp(12), dp(4), dp(12))
                        addView(name)
                        addView(info)
                    }
                    holder = Holder(name, info)
                    ll.tag = holder
                    row = ll
                } else {
                    row = convertView
                    holder = convertView.tag as Holder
                }
                val item = items[position]
                holder.name.text = item.name
                holder.info.text = describe(item)
                return row
            }
        }

        listView = ListView(ctx).apply {
            divider = ColorDrawable(Color.parseColor("#E5E7EB"))
            dividerHeight = 1
            adapter = this@RecordingsFragment.adapter
            setOnItemClickListener { _, _, position, _ ->
                if (position in 0 until items.size) showActions(items[position])
            }
        }
        emptyText = TextView(ctx).apply {
            text = "Belum ada rekaman."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6B7280"))
            visibility = View.GONE
        }
        val frame = FrameLayout(ctx)
        frame.addView(listView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        frame.addView(emptyText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(frame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        updateChips()
        return root
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        loadToken++ // batalkan pemuatan yang masih jalan
        ui.removeCallbacks(ticker)
        try { contDialog?.dismiss() } catch (_: Exception) {}
        contDialog = null
        contTimer = null
        contInfo = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try { appCtx?.unregisterReceiver(statusReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        super.onDestroy()
    }

    // --------------------------------------------------------------- daftar

    private fun selectKind(video: Boolean) {
        if (showVideo == video) return
        showVideo = video
        updateChips()
        refresh()
    }

    private fun refresh() {
        val ctx = context?.applicationContext ?: return
        val video = showVideo
        val token = ++loadToken
        Thread {
            val list: List<RecordingStore.Item> = try {
                RecordingStore.list(ctx, video)
            } catch (_: Exception) {
                emptyList()
            }
            ui.post {
                if (isAdded && token == loadToken) {
                    items.clear()
                    items.addAll(list)
                    adapter.notifyDataSetChanged()
                    updateEmpty()
                }
            }
            // durasi dibaca belakangan agar daftar tampil cepat
            for (item in list) {
                if (token != loadToken) return@Thread
                val d = RecordingStore.duration(ctx, item.uri)
                ui.post {
                    if (isAdded && token == loadToken) {
                        item.durationMs = d
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }.start()
    }

    private fun updateEmpty() {
        emptyText.text = if (showVideo) "Belum ada rekaman layar." else "Belum ada rekaman suara."
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        countText.text = if (items.isEmpty()) "" else "${items.size} rekaman"
    }

    private fun describe(item: RecordingStore.Item): String {
        val parts = ArrayList<String>()
        if (item.dateMs > 0) parts.add(dateFmt.format(Date(item.dateMs)))
        if (item.durationMs > 0) parts.add(PlayerDialog.clock(item.durationMs))
        parts.add(Formatter.formatShortFileSize(requireContext(), item.size))
        return parts.joinToString(" · ")
    }

    // --------------------------------------------------------------- aksi

    private fun showActions(item: RecordingStore.Item) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        labels.add("Putar")
        actions.add { play(item) }
        if (item.canContinue) {
            labels.add("Lanjutkan rekaman")
            actions.add { continueRecording(item) }
        }
        labels.add("Bagikan")
        actions.add { share(item) }
        labels.add("Hapus")
        actions.add { confirmDelete(item) }

        AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun play(item: RecordingStore.Item) {
        PlayerDialog.show(requireContext(), item.uri, item.name, describe(item), item.isVideo, true)
    }

    private fun share(item: RecordingStore.Item) {
        try {
            RecordingStore.share(requireContext(), item)
        } catch (e: Exception) {
            toast("Tidak bisa membagikan: ${e.message}")
        }
    }

    private fun confirmDelete(item: RecordingStore.Item) {
        AlertDialog.Builder(requireContext())
            .setTitle("Hapus rekaman?")
            .setMessage(item.name)
            .setPositiveButton("Hapus") { _, _ ->
                if (RecordingStore.delete(requireContext(), item)) {
                    toast("Rekaman dihapus")
                } else {
                    toast("Tidak bisa menghapus. Hapus lewat aplikasi File / Galeri.")
                }
                refresh()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ----------------------------------------------------- lanjutkan rekaman

    private fun continueRecording(item: RecordingStore.Item) {
        val ctx = requireContext()
        if (RecordingService.isRunning) {
            toast("Selesaikan rekaman yang sedang berjalan dulu")
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingContinue = item
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        // Rekaman baru harus berformat sama dengan file lama (sample rate, kanal)
        val info = try {
            AudioAppender.probe(ctx, item.uri, item.ext)
        } catch (e: Exception) {
            toast("Rekaman ini tidak bisa dilanjutkan: ${e.message}")
            return
        }

        showContinueDialog(item)

        val i = Intent(ctx, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_IS_SCREEN, false)
            putExtra(RecordingService.EXTRA_MODE, RecorderConfig.AUDIO_MIC)
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, info.sampleRate)
            putExtra(RecordingService.EXTRA_AUDIO_QUALITY, 1)
            putExtra(RecordingService.EXTRA_VIDEO_QUALITY, 1)
            putExtra(
                RecordingService.EXTRA_AUDIO_FORMAT,
                if (info.isWav) RecorderConfig.FMT_WAV else RecorderConfig.FMT_AAC
            )
            putExtra(RecordingService.EXTRA_CONTINUE_URI, item.uri.toString())
            putExtra(RecordingService.EXTRA_CHANNELS, info.channels)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            closeContinueDialog()
            toast("Gagal melanjutkan rekaman: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun showContinueDialog(item: RecordingStore.Item) {
        val ctx = requireContext()
        contItem = item
        contStartedAt = 0L

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        box.addView(TextView(ctx).apply {
            text = item.name
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextColor(Color.parseColor("#374151"))
        })
        val timer = TextView(ctx).apply {
            text = "00:00"
            textSize = 36f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#B91C1C"))
            setPadding(0, dp(12), 0, dp(4))
        }
        val info = TextView(ctx).apply {
            text = "Menyiapkan mikrofon..."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6B7280"))
        }
        val stop = Button(ctx).apply { text = "Stop & simpan" }
        stop.setOnClickListener {
            stop.isEnabled = false
            info.text = "Menyimpan dan menggabungkan..."
            try {
                ctx.startService(Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            } catch (_: Exception) {}
        }
        box.addView(timer)
        box.addView(info)
        box.addView(stop)

        contTimer = timer
        contInfo = info
        contDialog = AlertDialog.Builder(ctx)
            .setTitle("Melanjutkan rekaman")
            .setView(box)
            .setCancelable(false)
            .create()
        contDialog?.show()
    }

    private fun onContinueStarted() {
        contStartedAt = SystemClock.elapsedRealtime()
        contInfo?.text = "Sedang merekam. Ketuk Stop untuk menyimpan."
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    private fun onContinueFailed(msg: String) {
        closeContinueDialog()
        toast(msg)
    }

    private fun onContinueFinished(uri: String, path: String, durMs: Long, note: String) {
        val item = contItem
        closeContinueDialog()
        refresh()
        val ctx = context ?: return
        if (uri.isEmpty()) {
            toast(if (note.isNotEmpty()) note else "Rekaman selesai")
            return
        }
        val name = if (path.isNotEmpty()) path.substringAfterLast('/') else (item?.name ?: "Rekaman")
        val sub = "Durasi tambahan: ${PlayerDialog.clock(durMs)}\n\n" +
            if (note.isNotEmpty()) note else "Rekaman baru sudah digabung ke file yang sama."
        try {
            PlayerDialog.show(ctx, Uri.parse(uri), name, sub, false, false)
        } catch (_: Exception) {
            toast("Rekaman disimpan")
        }
    }

    private fun closeContinueDialog() {
        ui.removeCallbacks(ticker)
        try { contDialog?.dismiss() } catch (_: Exception) {}
        contDialog = null
        contTimer = null
        contInfo = null
        contItem = null
        contStartedAt = 0L
    }

    // --------------------------------------------------------------- util UI

    private fun toast(msg: String) {
        val c = context ?: return
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
    }

    private fun updateChips() {
        styleChip(chipAudio, !showVideo)
        styleChip(chipVideo, showVideo)
    }

    private fun styleChip(tv: TextView, selected: Boolean) {
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor(if (selected) "#075E54" else "#E5E7EB"))
        }
        tv.setTextColor(if (selected) Color.WHITE else Color.parseColor("#374151"))
    }

    private fun chip(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { onClick() }
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
}
