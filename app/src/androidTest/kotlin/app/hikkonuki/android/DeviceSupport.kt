package app.hikkonuki.android

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import app.hikkonuki.android.core.ExportJob
import app.hikkonuki.android.core.JobPersistence
import app.hikkonuki.android.core.MediaKind
import app.hikkonuki.android.platform.MediaStoreGallery
import java.io.File

object DeviceSupport {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The debug build's stand-in for a mounted camera. */
    val inbox: File get() = File(context.getExternalFilesDir(null), "usb_inbox").apply { mkdirs() }

    fun installAsset(asset: String, name: String): File {
        val target = File(inbox, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    fun clearInbox() {
        inbox.listFiles()?.forEach { it.delete() }
    }

    /** Removes everything this app put into DCIM/Hikkonuki, finished or pending. */
    fun clearGallery() {
        val resolver = context.contentResolver
        for (collection in listOf(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )) {
            val ids = ArrayList<Long>()
            val query = android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(MediaStoreGallery.RELATIVE_PATH))
            }
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), query, null)?.use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getLong(0)
            }
            ids.forEach { resolver.delete(ContentUris.withAppendedId(collection, it), null, null) }
        }
    }

    data class Published(val uri: Uri, val path: String, val dateTakenMillis: Long, val size: Long)

    fun published(name: String, kind: MediaKind): Published? {
        val collection = if (kind == MediaKind.VIDEO) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.SIZE)
        return context.contentResolver.query(
            collection, columns,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, MediaStoreGallery.RELATIVE_PATH), null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else Published(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1), cursor.getLong(2), cursor.getLong(3))
        }
    }

    data class Track(val mime: String, val format: MediaFormat, val samples: Int, val bytes: Long)

    /** Demuxes a file completely: proves it is well formed and counts what is in it. */
    fun tracks(path: String): List<Track> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            return (0 until extractor.trackCount).map { index ->
                val format = extractor.getTrackFormat(index)
                extractor.selectTrack(index)
                var samples = 0
                var bytes = 0L
                while (extractor.sampleSize >= 0) {
                    samples++
                    bytes += extractor.sampleSize
                    extractor.advance()
                }
                extractor.unselectTrack(index)
                Track(format.getString(MediaFormat.KEY_MIME)!!, format, samples, bytes)
            }
        } finally {
            extractor.release()
        }
    }
}

/** Remembers how each job ended: a finished batch deletes its rows and keeps only a summary. */
class RecordingPersistence(private val delegate: JobPersistence) : JobPersistence by delegate {
    val lastState = LinkedHashMap<Long, ExportJob>()

    override fun update(job: ExportJob) {
        lastState[job.id] = job
        delegate.update(job)
    }
}
