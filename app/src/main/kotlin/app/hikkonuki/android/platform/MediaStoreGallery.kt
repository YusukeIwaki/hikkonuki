package app.hikkonuki.android.platform

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import app.hikkonuki.android.core.ExportException
import app.hikkonuki.android.core.FailureReason
import app.hikkonuki.android.core.Gallery
import app.hikkonuki.android.core.GalleryEntry
import app.hikkonuki.android.core.MediaKind
import app.hikkonuki.android.core.PendingEntry
import java.io.File

/**
 * Imports land in DCIM/Hikkonuki, where Google Photos picks them up.
 *
 * Files are created as MediaStore "pending" entries and written through their real path,
 * so the encoder writes straight to the destination (no second multi-GB copy) while the
 * file stays hidden from other apps until it is complete. Publishing makes the system
 * scan it, which is when the date inside the file becomes the gallery date.
 */
class MediaStoreGallery(private val context: Context) : Gallery {
    private val resolver get() = context.contentResolver

    private fun collection(kind: MediaKind): Uri =
        if (kind == MediaKind.VIDEO) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun findExisting(displayName: String, kind: MediaKind): GalleryEntry? {
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        return resolver.query(collection(kind), columns, selection, arrayOf(displayName, RELATIVE_PATH), null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val size = cursor.getLong(1)
            if (size <= 0) return null
            GalleryEntry(ContentUris.withAppendedId(collection(kind), cursor.getLong(0)).toString(), size)
        }
    }

    override fun create(displayName: String, kind: MediaKind): PendingEntry {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(displayName, kind))
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection(kind), values) ?: throw ExportException(FailureReason.DESTINATION_UNAVAILABLE, "MediaStore insert returned null")
        val path = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        // The row exists but the file does not yet, and it can only be created through the
        // resolver; once it exists its owner may open it by path.
        val created = path != null && runCatching { resolver.openFileDescriptor(uri, "w")?.use { true } }.getOrNull() == true
        if (path == null || !created) {
            resolver.delete(uri, null, null)
            throw ExportException(FailureReason.DESTINATION_UNAVAILABLE, "pending file could not be created")
        }
        return object : PendingEntry {
            override val file = File(path)

            override fun publish(): GalleryEntry {
                val size = file.length()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return GalleryEntry(uri.toString(), size)
            }

            override fun discard() {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    override fun discardStale() {
        for (kind in MediaKind.entries) {
            val query = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
                )
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(RELATIVE_PATH, context.packageName))
            }
            val stale = ArrayList<Uri>()
            resolver.query(collection(kind), arrayOf(MediaStore.MediaColumns._ID), query, null)?.use { cursor ->
                while (cursor.moveToNext()) stale += ContentUris.withAppendedId(collection(kind), cursor.getLong(0))
            }
            stale.forEach { runCatching { resolver.delete(it, null, null) } }
        }
    }

    override fun availableBytes(): Long = StatFs(Environment.getExternalStorageDirectory().path).availableBytes

    companion object {
        const val FOLDER = "Hikkonuki"
        val RELATIVE_PATH = "${Environment.DIRECTORY_DCIM}/$FOLDER/"

        fun mimeOf(name: String, kind: MediaKind): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "png" -> "image/png"
            "heic" -> "image/heic"
            "dng" -> "image/x-adobe-dng"
            "jpg", "jpeg" -> "image/jpeg"
            else -> if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg"
        }
    }
}
