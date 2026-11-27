package app.hikkonuki.android.ui

import android.content.Intent
import android.net.Uri
import app.hikkonuki.android.core.BatchStatus
import app.hikkonuki.android.core.ExportBatch
import app.hikkonuki.android.core.ExportJob
import app.hikkonuki.android.core.ExportRequest
import app.hikkonuki.android.core.ExportStatus
import app.hikkonuki.android.core.MediaKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

fun mediaItem(name: String, size: Long = 1000, dateTaken: Long? = 1_780_000_000_000) = MediaItem(
    id = "content://usb/$name", uri = Uri.parse("content://usb/$name"), displayName = name,
    kind = if (name.endsWith(".mp4", true)) MediaKind.VIDEO else MediaKind.PHOTO,
    sizeBytes = size, dateTakenMillis = dateTaken,
)

class FakeLibrary : MediaLibrary {
    var snapshot = LibrarySnapshot(connected = false, items = emptyList())
    var details = HashMap<String, MediaDetails>()
    var rememberResult = true
    var present = true
    var loads = 0
    var gate: CompletableDeferred<Unit>? = null
    val remembered = ArrayList<Uri>()

    override suspend fun load(): LibrarySnapshot {
        loads++
        val result = snapshot
        gate?.await()
        return result
    }
    override suspend fun isConnected(): Boolean = snapshot.connected
    override suspend fun details(item: MediaItem): MediaDetails? = details[item.id]
    override suspend fun exists(item: MediaItem): Boolean = present
    override fun folderPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    override fun rememberFolder(uri: Uri): Boolean = rememberResult.also { if (it) remembered += uri }
}

class FakeExports : ExportController {
    override val jobs = MutableStateFlow<List<ExportJob>>(emptyList())
    override val batches = MutableStateFlow<List<ExportBatch>>(emptyList())
    val exported = ArrayList<List<ExportRequest>>()
    val cancelled = ArrayList<Long>()
    var resumed = 0
    var cleared = 0

    override fun export(requests: List<ExportRequest>) {
        exported += requests
        batches.value = listOf(ExportBatch(id = 1, status = BatchStatus.ACTIVE))
        jobs.value = jobs.value + requests.mapIndexed { index, request ->
            ExportJob(
                id = jobs.value.size + index + 1L, batchId = 1, sourceId = request.sourceId, displayName = request.displayName,
                kind = request.kind, sourceSizeBytes = request.sizeBytes, status = ExportStatus.QUEUED,
            )
        }
    }

    /** What the engine does when the last file is through: rows go, the summary stays. */
    fun finishAll(succeeded: Int, failed: Int, atMillis: Long) {
        batches.value = listOf(ExportBatch(1, BatchStatus.DONE, succeeded + failed, succeeded, failed, atMillis, atMillis))
        jobs.value = emptyList()
    }

    override fun resume() { resumed++ }
    override fun cancel(jobId: Long) { cancelled += jobId }
    override fun clearHistory() {
        cleared++
        jobs.value = emptyList()
        batches.value = emptyList()
    }
}
