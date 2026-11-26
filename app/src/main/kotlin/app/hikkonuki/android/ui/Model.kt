package app.hikkonuki.android.ui

import android.content.Intent
import android.net.Uri
import app.hikkonuki.android.core.ExportBatch
import app.hikkonuki.android.core.ExportJob
import app.hikkonuki.android.core.ExportRequest
import app.hikkonuki.android.core.MediaKind
import kotlinx.coroutines.flow.StateFlow

data class MediaItem(
    /** Absolute path or content URI string; also the export source id. */
    val id: String,
    val uri: Uri,
    val displayName: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    val dateTakenMillis: Long?,
    val width: Int = 0,
    val height: Int = 0,
    val durationMillis: Long? = null,
    val mimeType: String = "",
    val sourceLabel: String = "",
) {
    val isVideo: Boolean get() = kind == MediaKind.VIDEO
    fun toRequest() = ExportRequest(id, displayName, kind, sizeBytes)
}

data class MediaDetails(val width: Int, val height: Int, val durationMillis: Long?, val dateTakenMillis: Long?)

data class LibrarySnapshot(
    val connected: Boolean,
    val items: List<MediaItem>,
    /** Folders the user has granted; watched for the camera coming back. */
    val watchedFolders: List<String> = emptyList(),
)

/** What the screens need from the camera side. Implemented over USB storage on a device. */
interface MediaLibrary {
    suspend fun load(): LibrarySnapshot
    suspend fun isConnected(): Boolean
    /** Resolution, duration and embedded date: read lazily, it costs a USB round trip per file. */
    suspend fun details(item: MediaItem): MediaDetails?
    suspend fun exists(item: MediaItem): Boolean
    fun folderPickerIntent(): Intent
    fun rememberFolder(uri: Uri): Boolean
}

/** What the screens need from the export side: two lists to show and a few commands. */
interface ExportController {
    /** Rows of the latest batch. */
    val jobs: StateFlow<List<ExportJob>>
    /** Newest first. */
    val batches: StateFlow<List<ExportBatch>>
    fun export(requests: List<ExportRequest>)
    fun sourcesAvailable(requests: List<ExportRequest>)
    fun resume()
    fun cancel(jobId: Long)
    fun clearHistory()
}
