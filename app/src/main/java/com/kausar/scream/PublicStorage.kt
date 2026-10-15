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
 * Simpan ke folder publik:
 * - Audio → Musik (Music)
 * - Video → Movies
 * Bukan folder data aplikasi.
 */
object PublicStorage {

    data class Target(val file: File?, val uri: Uri?, val displayPath: String)

    fun prepareAudioFile(ctx: Context, name: String, mime: String): Target {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/KausarRecorder")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Gagal membuat entri Musik")
            Target(null, uri, "Musik/KausarRecorder/$name")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "KausarRecorder")
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
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/KausarRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Gagal membuat entri Movies")
            Target(null, uri, "Movies/KausarRecorder/$name")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "KausarRecorder")
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

    /** Hasil penyimpanan ke folder publik. uri = alamat content:// untuk diputar/dibagikan. */
    data class Published(val uri: Uri?, val displayPath: String, val name: String)

    /** Salin file sementara app ke Musik/Movies publik, lalu hapus sementara. */
    fun publishFileEx(ctx: Context, temp: File, isVideo: Boolean, mime: String): Published {
        val name = temp.name
        val target = if (isVideo) prepareVideoFile(ctx, name) else prepareAudioFile(ctx, name, mime)
        openOut(ctx, target).use { out ->
            FileInputStream(temp).use { inp -> inp.copyTo(out) }
        }
        finishPending(ctx, target)
        // Android 9 ke bawah: daftarkan file ke MediaStore agar punya Uri & muncul di daftar rekaman
        val uri = target.uri ?: target.file?.let { registerLegacy(ctx, it, mime, isVideo) }
        try { temp.delete() } catch (_: Exception) {}
        return Published(uri, target.displayPath, name)
    }

    /** Versi lama (tetap ada agar kode lain tidak rusak). */
    fun publishFile(ctx: Context, temp: File, isVideo: Boolean, mime: String): String =
        publishFileEx(ctx, temp, isVideo, mime).displayPath

    /** Android < 10: tulis baris MediaStore manual dan kembalikan Uri-nya. */
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
            ctx.contentResolver.insert(collection, v)
        } catch (_: Exception) {
            null
        }
    }

    /** Minta sistem membaca ulang metadata (durasi/ukuran) setelah isi file diubah. */
    @Suppress("DEPRECATION")
    fun rescan(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val p = c.getString(0)
                    if (!p.isNullOrEmpty()) MediaScannerConnection.scanFile(ctx, arrayOf(p), null, null)
                }
            }
        } catch (_: Exception) {}
    }
}
