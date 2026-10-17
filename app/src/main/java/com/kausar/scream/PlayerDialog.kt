package com.kausar.scream

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.util.Locale

object PlayerDialog {

    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** Ambil Activity asli (bukan Context hasil wrap locale). */
    private fun activityOf(ctx: Context?): Activity? {
        var c: Context? = ctx
        var depth = 0
        while (c != null && depth < 8) {
            if (c is Activity) return c
            c = if (c is ContextWrapper) c.baseContext else null
            depth++
        }
        return null
    }

    private fun canShowDialog(act: Activity?): Boolean {
        if (act == null) return false
        if (act.isFinishing) return false
        if (Build.VERSION.SDK_INT >= 17 && act.isDestroyed) return false
        // window token wajib ada; tanpa ini → "token null is not valid"
        val w = act.window
        if (w == null || w.decorView.windowToken == null) return false
        return true
    }

    /** Context hanya untuk string (locale), bukan untuk AlertDialog. */
    private fun strCtx(act: Activity): Context {
        return try { LanguageManager.wrap(act) } catch (_: Exception) { act }
    }

    private fun runOnActivity(ctx: Context, block: (Activity) -> Unit) {
        val run = Runnable {
            try {
                val act = activityOf(ctx)
                if (!canShowDialog(act)) return@Runnable
                block(act!!)
            } catch (e: Exception) {
                try {
                    Toast.makeText(ctx.applicationContext, e.message ?: "error", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run()
        else Handler(Looper.getMainLooper()).post(run)
    }

    fun showAfterRecording(ctx: Context, uriStr: String, path: String, durText: String, note: String = "") {
        // Tunda sedikit agar activity kembali ke foreground setelah service stop
        Handler(Looper.getMainLooper()).postDelayed({
            runOnActivity(ctx) { act ->
                val s = strCtx(act)
                val durationLabel = s.getString(R.string.duration)
                val locationLabel = s.getString(R.string.player_location)
                val subtitle = "$durationLabel: $durText" +
                    (if (path.isNotEmpty()) "\n\n$locationLabel:\n$path" else "") +
                    (if (note.isNotEmpty()) "\n\n$note" else "")

                if (uriStr.isEmpty() && path.isEmpty()) {
                    if (!canShowDialog(act)) return@runOnActivity
                    AlertDialog.Builder(act)
                        .setTitle(s.getString(R.string.player_stopped))
                        .setMessage(subtitle.ifEmpty { s.getString(R.string.status_stopped) })
                        .setPositiveButton(s.getString(R.string.player_ok), null)
                        .show()
                    return@runOnActivity
                }

                val items = arrayOf(
                    s.getString(R.string.action_save),
                    s.getString(R.string.action_play),
                    s.getString(R.string.action_share),
                    s.getString(R.string.action_share_save),
                    s.getString(R.string.action_cancel)
                )

                if (!canShowDialog(act)) return@runOnActivity
                AlertDialog.Builder(act)
                    .setTitle(s.getString(R.string.action_title))
                    .setMessage(subtitle)
                    .setItems(items) { _, which ->
                        try {
                            when (which) {
                                0 -> Toast.makeText(act, s.getString(R.string.action_saved), Toast.LENGTH_SHORT).show()
                                1 -> playUri(act, uriStr, path, durText)
                                2 -> share(act, uriStr, path)
                                3 -> {
                                    share(act, uriStr, path)
                                    Toast.makeText(act, s.getString(R.string.action_saved), Toast.LENGTH_SHORT).show()
                                }
                                4 -> {
                                    try {
                                        if (path.isNotEmpty()) {
                                            val f = File(path)
                                            if (f.exists() && f.canWrite()) f.delete()
                                        }
                                        if (uriStr.isNotEmpty() && uriStr.startsWith("content:")) {
                                            try { act.contentResolver.delete(Uri.parse(uriStr), null, null) } catch (_: Exception) {}
                                        }
                                    } catch (_: Exception) {}
                                    Toast.makeText(act, s.getString(R.string.action_discarded), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(act.applicationContext, e.message ?: "err", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(s.getString(R.string.settings_close), null)
                    .show()
            }
        }, 350)
    }

    private fun playUri(ctx: Context, uriStr: String, path: String, durText: String) {
        runOnActivity(ctx) { act ->
            val s = strCtx(act)
            if (uriStr.isEmpty()) {
                Toast.makeText(act, s.getString(R.string.player_fail), Toast.LENGTH_LONG).show()
                return@runOnActivity
            }
            val name = path.substringAfterLast('/').ifEmpty { s.getString(R.string.player_recording) }
            val locationLabel = s.getString(R.string.player_location)
            val durationLabel = s.getString(R.string.duration)
            val subtitle = "$durationLabel: $durText" +
                (if (path.isNotEmpty()) "\n\n$locationLabel:\n$path" else "")
            show(
                act,
                Uri.parse(uriStr),
                "${s.getString(R.string.player_title)} · $name",
                subtitle,
                path.endsWith(".mp4", true) || uriStr.contains("video", true),
                true
            )
        }
    }

    private fun share(ctx: Context, uriStr: String, path: String) {
        val act = activityOf(ctx) ?: return
        val s = strCtx(act)
        try {
            if (uriStr.isEmpty()) {
                Toast.makeText(act, s.getString(R.string.player_fail), Toast.LENGTH_SHORT).show()
                return
            }
            val uri = Uri.parse(uriStr)
            val isVideo = path.endsWith(".mp4", true)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (isVideo) "video/*" else "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            act.startActivity(Intent.createChooser(send, s.getString(R.string.share_via)))
        } catch (e: Exception) {
            Toast.makeText(act.applicationContext, e.message ?: "share", Toast.LENGTH_SHORT).show()
        }
    }

    fun show(ctx: Context, uri: Uri, title: String, subtitle: String, isVideo: Boolean, autoPlay: Boolean) {
        runOnActivity(ctx) { act ->
            val s = strCtx(act)
            val density = act.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density + 0.5f).toInt()

            val handler = Handler(Looper.getMainLooper())
            var ready = false
            var dragging = false
            var dismissed = false

            val player: MediaPlayer? = if (!isVideo) MediaPlayer() else null
            val videoView: VideoView? = if (isVideo) VideoView(act) else null

            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(4))
            }
            if (subtitle.isNotEmpty()) {
                root.addView(TextView(act).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(Color.parseColor("#6B7280"))
                    setPadding(0, 0, 0, dp(10))
                })
            }
            if (videoView != null) {
                val frame = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
                frame.addView(
                    videoView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )
                root.addView(
                    frame,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300))
                )
            }

            val seek = SeekBar(act).apply { isEnabled = false; max = 1 }
            val cur = TextView(act).apply { text = "00:00"; textSize = 12f }
            val tot = TextView(act).apply { text = "--:--"; textSize = 12f; gravity = Gravity.END }
            val times = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(cur, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(tot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val btnBack = Button(act).apply { text = s.getString(R.string.player_back); isEnabled = false }
            val btnPlay = Button(act).apply { text = s.getString(R.string.player_play); isEnabled = false }
            val btnFwd = Button(act).apply { text = s.getString(R.string.player_fwd); isEnabled = false }
            val controls = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btnBack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(btnPlay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(btnFwd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(seek)
            root.addView(times)
            root.addView(controls)

            fun posMs(): Int = try {
                if (videoView != null) videoView.currentPosition else player!!.currentPosition
            } catch (_: Exception) { 0 }

            fun durMs(): Int = try {
                if (videoView != null) videoView.duration else player!!.duration
            } catch (_: Exception) { 0 }

            fun seekP(ms: Int) {
                try {
                    if (videoView != null) videoView.seekTo(ms) else player!!.seekTo(ms)
                } catch (_: Exception) {}
            }

            fun isPlaying(): Boolean = try {
                if (videoView != null) videoView.isPlaying else player!!.isPlaying
            } catch (_: Exception) { false }

            fun doPlay() {
                try {
                    if (videoView != null) videoView.start() else player!!.start()
                    btnPlay.text = s.getString(R.string.player_pause)
                } catch (_: Exception) {}
            }

            fun doPause() {
                try {
                    if (videoView != null) videoView.pause() else player!!.pause()
                    btnPlay.text = s.getString(R.string.player_play)
                } catch (_: Exception) {}
            }

            val tick = object : Runnable {
                override fun run() {
                    if (dismissed || !ready) return
                    if (!dragging) {
                        val p = posMs()
                        seek.progress = p
                        cur.text = clock(p.toLong())
                    }
                    handler.postDelayed(this, 400)
                }
            }

            fun onReady() {
                if (dismissed) return
                ready = true
                val d = durMs().coerceAtLeast(1)
                seek.max = d
                seek.isEnabled = true
                tot.text = clock(d.toLong())
                btnBack.isEnabled = true
                btnPlay.isEnabled = true
                btnFwd.isEnabled = true
                handler.post(tick)
                if (autoPlay) doPlay()
            }

            fun onDone() {
                btnPlay.text = s.getString(R.string.player_play)
                seek.progress = 0
                cur.text = "00:00"
            }

            fun onError() {
                try { Toast.makeText(act, s.getString(R.string.player_fail), Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                btnPlay.text = s.getString(R.string.player_play)
            }

            btnPlay.setOnClickListener {
                if (!ready || dismissed) return@setOnClickListener
                if (isPlaying()) doPause() else doPlay()
            }
            btnBack.setOnClickListener {
                if (!ready || dismissed) return@setOnClickListener
                val p = (posMs() - 10_000).coerceAtLeast(0)
                seekP(p)
                seek.progress = p
                cur.text = clock(p.toLong())
            }
            btnFwd.setOnClickListener {
                if (!ready || dismissed) return@setOnClickListener
                val p = (posMs() + 10_000).coerceAtMost(durMs())
                seekP(p)
                seek.progress = p
                cur.text = clock(p.toLong())
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) cur.text = clock(progress.toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    dragging = false
                    if (ready) seekP(sb?.progress ?: 0)
                }
            })

            try {
                if (videoView != null) {
                    videoView.setOnPreparedListener { onReady() }
                    videoView.setOnCompletionListener { onDone() }
                    videoView.setOnErrorListener { _, _, _ -> onError(); true }
                    videoView.setVideoURI(uri)
                } else {
                    val p = player!!
                    p.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    p.setDataSource(act, uri)
                    p.setOnPreparedListener { onReady() }
                    p.setOnCompletionListener { onDone() }
                    p.setOnErrorListener { _, _, _ -> onError(); true }
                    p.prepareAsync()
                }
            } catch (e: Exception) {
                try { player?.release() } catch (_: Exception) {}
                try {
                    Toast.makeText(act, s.getString(R.string.player_fail_msg, e.message ?: ""), Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
                return@runOnActivity
            }

            if (!canShowDialog(act)) {
                try { player?.release() } catch (_: Exception) {}
                return@runOnActivity
            }
            try {
                val dialog = AlertDialog.Builder(act)
                    .setTitle(title)
                    .setView(root)
                    .setPositiveButton(s.getString(R.string.player_close), null)
                    .create()
                dialog.setOnDismissListener {
                    dismissed = true
                    handler.removeCallbacksAndMessages(null)
                    try { videoView?.stopPlayback() } catch (_: Exception) {}
                    try { player?.release() } catch (_: Exception) {}
                }
                dialog.show()
            } catch (e: Exception) {
                dismissed = true
                try { player?.release() } catch (_: Exception) {}
                try { Toast.makeText(act.applicationContext, e.message ?: "dialog", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            }
        }
    }
}
