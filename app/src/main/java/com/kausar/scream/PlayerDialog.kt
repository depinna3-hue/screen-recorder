package com.kausar.scream

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
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
import java.util.Locale

/**
 * Pemutar rekaman dalam dialog (suara = MediaPlayer, layar = VideoView).
 * Kontrol: Putar/Jeda, geser posisi, mundur/maju 10 detik.
 */
object PlayerDialog {

    /** 75000 → "01:15", 3725000 → "1:02:05" */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /**
     * Dipanggil setelah rekaman selesai (suara maupun layar).
     * Bila alamat file (uri) tidak ada, tampilkan dialog info biasa seperti sebelumnya.
     */
    fun showAfterRecording(ctx: Context, uriStr: String, path: String, durText: String) {
        val subtitle = "Durasi: $durText" + if (path.isNotEmpty()) "\n\nLokasi:\n$path" else ""
        if (uriStr.isEmpty()) {
            AlertDialog.Builder(ctx)
                .setTitle("Rekaman dihentikan")
                .setMessage(subtitle)
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val name = path.substringAfterLast('/').ifEmpty { "Rekaman" }
        show(ctx, Uri.parse(uriStr), "Rekaman selesai · $name", subtitle, path.endsWith(".mp4", true), false)
    }

    fun show(ctx: Context, uri: Uri, title: String, subtitle: String, isVideo: Boolean, autoPlay: Boolean) {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        val handler = Handler(Looper.getMainLooper())
        var ready = false
        var dragging = false
        var dismissed = false

        val player: MediaPlayer? = if (!isVideo) MediaPlayer() else null
        val videoView: VideoView? = if (isVideo) VideoView(ctx) else null

        // ---------------------------------------------------------- tampilan
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        if (subtitle.isNotEmpty()) {
            root.addView(TextView(ctx).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#6B7280"))
                setPadding(0, 0, 0, dp(10))
            })
        }
        if (videoView != null) {
            val frame = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
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

        val seek = SeekBar(ctx).apply { isEnabled = false; max = 1 }
        val cur = TextView(ctx).apply { text = "00:00"; textSize = 12f }
        val tot = TextView(ctx).apply { text = "--:--"; textSize = 12f; gravity = Gravity.END }
        val times = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cur, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val btnBack = Button(ctx).apply { text = "« 10 dtk"; isEnabled = false }
        val btnPlay = Button(ctx).apply { text = "Putar"; isEnabled = false }
        val btnFwd = Button(ctx).apply { text = "10 dtk »"; isEnabled = false }
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnBack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnPlay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnFwd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(seek)
        root.addView(times)
        root.addView(controls)

        // ------------------------------------------------------ akses pemutar
        fun posMs(): Int = try {
            if (videoView != null) videoView.currentPosition else player!!.currentPosition
        } catch (_: Exception) { 0 }

        fun durMs(): Int = try {
            if (videoView != null) videoView.duration else player!!.duration
        } catch (_: Exception) { 0 }

        fun playing(): Boolean = try {
            if (videoView != null) videoView.isPlaying else player!!.isPlaying
        } catch (_: Exception) { false }

        val tick = object : Runnable {
            override fun run() {
                if (dismissed) return
                if (ready && !dragging) {
                    val p = posMs()
                    seek.progress = p
                    cur.text = clock(p.toLong())
                }
                if (playing()) handler.postDelayed(this, 250)
            }
        }

        fun startP() {
            try {
                if (videoView != null) videoView.start() else player!!.start()
                btnPlay.text = "Jeda"
                handler.removeCallbacks(tick)
                handler.post(tick)
            } catch (_: Exception) {}
        }

        fun pauseP() {
            try {
                if (videoView != null) videoView.pause() else player!!.pause()
            } catch (_: Exception) {}
            btnPlay.text = "Putar"
        }

        fun seekP(ms: Int) {
            try {
                if (videoView != null) videoView.seekTo(ms) else player!!.seekTo(ms)
            } catch (_: Exception) {}
        }

        val onReady: () -> Unit = {
            ready = true
            val d = durMs()
            seek.max = d.coerceAtLeast(1)
            tot.text = clock(d.toLong())
            seek.isEnabled = true
            btnPlay.isEnabled = true
            btnBack.isEnabled = true
            btnFwd.isEnabled = true
            if (autoPlay) startP()
        }

        val onDone: () -> Unit = {
            btnPlay.text = "Putar"
            seek.progress = 0
            cur.text = "00:00"
            seekP(0)
        }

        val onError: () -> Unit = {
            Toast.makeText(ctx, "Gagal memutar rekaman ini", Toast.LENGTH_LONG).show()
        }

        // -------------------------------------------------------- tombol
        btnPlay.setOnClickListener {
            if (ready) {
                if (playing()) pauseP() else startP()
            }
        }
        btnBack.setOnClickListener {
            if (ready) {
                val p = (posMs() - 10_000).coerceAtLeast(0)
                seekP(p)
                seek.progress = p
                cur.text = clock(p.toLong())
            }
        }
        btnFwd.setOnClickListener {
            if (ready) {
                val p = (posMs() + 10_000).coerceAtMost(durMs())
                seekP(p)
                seek.progress = p
                cur.text = clock(p.toLong())
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) cur.text = clock(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
                if (ready) seekP(sb?.progress ?: 0)
            }
        })

        // -------------------------------------------------------- siapkan media
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
                p.setDataSource(ctx, uri)
                p.setOnPreparedListener { onReady() }
                p.setOnCompletionListener { onDone() }
                p.setOnErrorListener { _, _, _ -> onError(); true }
                p.prepareAsync()
            }
        } catch (e: Exception) {
            try { player?.release() } catch (_: Exception) {}
            Toast.makeText(ctx, "Tidak bisa memutar: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("Tutup", null)
            .create()
        dialog.setOnDismissListener {
            dismissed = true
            handler.removeCallbacksAndMessages(null)
            try { videoView?.stopPlayback() } catch (_: Exception) {}
            try { player?.release() } catch (_: Exception) {}
        }
        try {
            dialog.show()
        } catch (_: Exception) {
            dismissed = true
            try { player?.release() } catch (_: Exception) {}
        }
    }
}
