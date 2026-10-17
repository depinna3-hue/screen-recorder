package com.kausar.scream

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * Membaca daftar rekaman milik app ini dari MediaStore
 * (Musik/KausarRecorder untuk suara, Movies/KausarRecorder untuk layar).
 */
object RecordingStore {

    data class Item(
        val uri: Uri,
        val name: String,
        val size: Long,
        val dateMs: Long,
        val isVideo: Boolean,
        var durationMs: Long = -1L
    ) {
        val ext: String get() = name.substringAfterLast('.', "").lowercase()

        /** Hanya rekaman suara .m4a (AAC) dan .wav yang bisa disambung. */
        val canContinue: Boolean get() = !isVideo && (ext == "m4a" || ext == "wav")
    }

    private const val FOLDER = "KausarRecorder"

    @Suppress("DEPRECATION")
    fun list(ctx: Context, video: Boolean): List<Item> {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )

        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            val root = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
            selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
            args = arrayOf("$root/$FOLDER/%")
        } else {
            selection = MediaStore.MediaColumns.DATA + " LIKE ?"
            args = arrayOf("%/$FOLDER/%")
        }

        val out = ArrayList<Item>()
        // SecurityException (izin media ditolak) / error provider tidak boleh membuat aplikasi crash
        try {
        ctx.contentResolver.query(
            collection, projection, selection, args,
            MediaStore.MediaColumns.DATE_ADDED + " DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out.add(
                    Item(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(iName) ?: "rekaman",
                        size = c.getLong(iSize),
                        dateMs = c.getLong(iDate) * 1000L,
                        isVideo = video
                    )
                )
            }
        }
        } catch (_: Exception) {
        }
        return out
    }

    /** Durasi (ms) dibaca langsung dari file supaya tetap benar setelah rekaman disambung. */
    fun duration(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
        } catch (_: Exception) {
            -1L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    fun nameOf(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    /** true bila terhapus. Gagal (mis. file dari instalasi lama) → false. */
    fun delete(ctx: Context, item: Item): Boolean {
        return try {
            ctx.contentResolver.delete(item.uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    fun share(ctx: Context, item: Item) {
        val mime = when {
            item.isVideo -> "video/mp4"
            item.ext == "wav" -> "audio/wav"
            item.ext == "m4a" -> "audio/mp4"
            else -> "audio/*"
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, item.uri)
            clipData = ClipData.newRawUri(item.name, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Bagikan rekaman")
        if (ctx !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }
}
