package com.kausar.scream

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

/**
 * Folder publik:
 *   Musik/KausarRecorder/Audio
 *   Movies/KausarRecorder/Screen
 */
object PublicStorage {

    const val ROOT = "KausarRecorder"
    const val AUDIO_SUB = "Audio"
    const val SCREEN_SUB = "Screen"

    data class Target(val file: File?, val uri: Uri?, val displayPath: String)

    /** Buat struktur folder agar muncul di file manager (Android 9 kebawah & cache path). */
    fun ensureFolders(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION")
                val music = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "$ROOT/$AUDIO_SUB")
                @Suppress("DEPRECATION")
                val movies = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "$ROOT/$SCREEN_SUB")
                music.mkdirs()
                movies.mkdirs()
                // file penanda agar folder terlihat
                File(music, ".nomedia").apply { if (!exists()) createNewFile() }
                // jangan .nomedia di video agar muncul di galeri; buat placeholder kosong opsional
                File(movies, ".keep").apply { if (!exists()) writeText("KausarRecorder") }
            } else {
                // API 29+: folder muncul saat file pertama ditulis lewat MediaStore.
                // Tulis file penanda ringan agar folder langsung terlihat.
                trySeed(ctx, false)
                trySeed(ctx, true)
            }
        } catch (_: Exception) {}
    }

    private fun trySeed(ctx: Context, video: Boolean) {
        try {
            val name = if (video) ".keep_screen.txt" else ".keep_audio.txt"
            val mime = "text/plain"
            val values = ContentValues().apply {
                if (video) {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/$ROOT/$SCREEN_SUB")
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                } else {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/$ROOT/$AUDIO_SUB")
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
            }
            val collection = if (video) MediaStore.Files.getContentUri("external")
            else MediaStore.Files.getContentUri("external")
            // Hindari spam: cek dulu tidak perlu; insert boleh gagal jika sudah ada
            ctx.contentResolver.insert(collection, values)
        } catch (_: Exception) {}
    }

    fun prepareAudioFile(ctx: Context, name: String, mime: String): Target {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/$ROOT/$AUDIO_SUB")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Gagal membuat entri Musik")
            Target(null, uri, "Music/$ROOT/$AUDIO_SUB/$name")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "$ROOT/$AUDIO_SUB")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            Target(f, null, f.absolutePath)
        }
    }

    fun prepareVideoFile(ctx: Context, name: String): Target {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/$ROOT/$SCREEN_SUB")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Gagal membuat entri Movies")
            Target(null, uri, "Movies/$ROOT/$SCREEN_SUB/$name")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "$ROOT/$SCREEN_SUB")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            Target(f, null, f.absolutePath)
        }
    }

    fun openOut(ctx: Context, target: Target): OutputStream {
        return if (target.uri != null) {
            ctx.contentResolver.openOutputStream(target.uri)
                ?: throw IllegalStateException("Tidak bisa buka URI keluaran")
        } else {
            target.file!!.outputStream()
        }
    }

    fun finishPending(ctx: Context, target: Target) {
        if (Build.VERSION.SDK_INT >= 29 && target.uri != null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            try { ctx.contentResolver.update(target.uri, values, null, null) } catch (_: Exception) {}
        }
    }

    data class Published(val uri: Uri?, val displayPath: String, val name: String)

    fun publishFileEx(ctx: Context, temp: File, isVideo: Boolean, mime: String): Published {
        val name = temp.name
        val target = if (isVideo) prepareVideoFile(ctx, name) else prepareAudioFile(ctx, name, mime)
        openOut(ctx, target).use { out ->
            FileInputStream(temp).use { inp -> inp.copyTo(out) }
        }
        finishPending(ctx, target)
        val uri = target.uri ?: target.file?.let { registerLegacy(ctx, it, mime, isVideo) }
        try { temp.delete() } catch (_: Exception) {}
        return Published(uri, target.displayPath, name)
    }

    fun publishFile(ctx: Context, temp: File, isVideo: Boolean, mime: String): String =
        publishFileEx(ctx, temp, isVideo, mime).displayPath

    @Suppress("DEPRECATION")
    private fun registerLegacy(ctx: Context, f: File, mime: String, isVideo: Boolean): Uri? {
        return try {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, f.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.TITLE, f.nameWithoutExtension)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.SIZE, f.length())
            }
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val uri = ctx.contentResolver.insert(collection, v)
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(mime), null)
            uri
        } catch (_: Exception) {
            null
        }
    }

    /** Paksa MediaStore memindai ulang file (setelah append). */
    fun rescan(ctx: Context, uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                ctx.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val path = uri.path
                if (path != null) {
                    MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
                }
            }
        } catch (_: Exception) {}
    }
}
