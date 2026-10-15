package com.kausar.scream

import android.content.ContentValues
import android.content.Context
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

    /** Salin file sementara app ke Musik/Movies publik, lalu hapus sementara. */
    fun publishFile(ctx: Context, temp: File, isVideo: Boolean, mime: String): String {
        val name = temp.name
        val target = if (isVideo) prepareVideoFile(ctx, name) else prepareAudioFile(ctx, name, mime)
        openOut(ctx, target).use { out ->
            FileInputStream(temp).use { inp -> inp.copyTo(out) }
        }
        finishPending(ctx, target)
        try { temp.delete() } catch (_: Exception) {}
        return target.displayPath
    }
}
