package app.hikkonuki.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MediaKind { PHOTO, VIDEO }

enum class ExportStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
}

enum class ExportOutcome { CONVERTED, ORIGINAL, COPIED, ALREADY_IMPORTED }

enum class BatchStatus { ACTIVE, DONE }

/**
 * One export session. While ACTIVE it owns the job rows; once everything in it has
 * finished, the rows are deleted and only this summary (the final result) remains.
 * Unplugging the camera fails whatever was left, so that batch finishes too.
 */
data class ExportBatch(
    val id: Long = 0,
    val status: BatchStatus = BatchStatus.ACTIVE,
    val totalCount: Int = 0,
    val succeededCount: Int = 0,
    val failedCount: Int = 0,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
)

data class ExportJob(
    val id: Long = 0,
    val batchId: Long = 0,
    val sourceId: String,
    val displayName: String,
    val kind: MediaKind,
    val sourceSizeBytes: Long,
    val status: ExportStatus = ExportStatus.QUEUED,
    val progressPercent: Int = 0,
    val outputUri: String? = null,
    val outputSizeBytes: Long = 0,
    val outcome: ExportOutcome? = null,
    /** Human readable note: what was produced, or why the original was kept. */
    val detail: String? = null,
    val errorMessage: String? = null,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
)

data class ExportRequest(val sourceId: String, val displayName: String, val kind: MediaKind, val sizeBytes: Long) {
    /** The same file reached through another URI after a replug is still the same file. */
    val fileKey: String get() = "${displayName.lowercase()}|$sizeBytes"
}

val ExportJob.fileKey: String get() = "${displayName.lowercase()}|$sourceSizeBytes"

/** Row storage only; every rule lives in [ExportQueue] so it can be tested without SQLite. */
interface JobPersistence {
    fun loadJobs(): List<ExportJob>
    fun insert(job: ExportJob): Long
    fun update(job: ExportJob)
    fun delete(ids: Collection<Long>)
    fun loadBatches(): List<ExportBatch>
    fun insertBatch(batch: ExportBatch): Long
    fun updateBatch(batch: ExportBatch)
    fun deleteBatches(ids: Collection<Long>)
}

/**
 * The export list and its rules.
 *
 * - At most [MAX_BATCHES] batches are kept: the current one plus one finished result.
 * - [jobs] holds the rows of the latest batch; screens show those while it is ACTIVE and
 *   the batch summary once it is DONE.
 * - User cancellation deletes the row outright (no "cancelled" tombstones).
 *
 * State changes are persisted immediately, so killing the process at any point loses at
 * most the progress of the file being written. Progress percentages are memory-only:
 * they change many times a second.
 */
class ExportQueue(private val persistence: JobPersistence, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val batchState = MutableStateFlow(persistence.loadBatches().sortedByDescending { it.id })
    private val state = MutableStateFlow(persistence.loadJobs().sortedBy { it.id })

    /** Newest first. */
    val batches: StateFlow<List<ExportBatch>> = batchState.asStateFlow()

    /** Rows of the latest batch. */
    val jobs: StateFlow<List<ExportJob>> = state.asStateFlow()

    private fun find(id: Long): ExportJob? = state.value.firstOrNull { it.id == id }

    private fun replace(id: Long, persist: Boolean = true, change: (ExportJob) -> ExportJob): ExportJob? = synchronized(lock) {
        val current = find(id) ?: return null
        val updated = change(current).copy(updatedAtMillis = now())
        if (persist) persistence.update(updated)
        state.value = state.value.map { if (it.id == id) updated else it }
        updated
    }

    /** After a process death nothing is running any more: put interrupted work back in line. */
    fun recover(): Int = synchronized(lock) {
        val interrupted = state.value.filter { it.status == ExportStatus.RUNNING }
        interrupted.forEach { requeue(it.id) }
        interrupted.size
    }

    private fun activeBatch(): ExportBatch? = batchState.value.firstOrNull()?.takeIf { it.status == BatchStatus.ACTIVE }

    private fun createBatch(): ExportBatch {
        val time = now()
        val batch = ExportBatch(createdAtMillis = time, updatedAtMillis = time).let { it.copy(id = persistence.insertBatch(it)) }
        batchState.value = listOf(batch) + batchState.value
        pruneBatches()
        return batch
    }

    private fun pruneBatches() {
        val old = batchState.value.drop(MAX_BATCHES)
        if (old.isEmpty()) return
        val ids = old.map { it.id }.toSet()
        removeAll(state.value.filter { it.batchId in ids })
        persistence.deleteBatches(ids)
        batchState.value = batchState.value.take(MAX_BATCHES)
    }

    /**
     * Adds files and returns how many were actually added. They join the batch that is
     * still running; a new batch starts when the latest one is DONE or there is none.
     */
    fun enqueue(requests: List<ExportRequest>): Int = synchronized(lock) {
        var added = 0
        var batch = activeBatch()
        val seen = HashSet<String>()
        for (request in requests) {
            if (!seen.add(request.fileKey)) continue
            val existing = if (batch == null) null else state.value.firstOrNull { it.fileKey == request.fileKey }
            when (existing?.status) {
                // Already done or already in line in this batch: never queue a file twice.
                ExportStatus.QUEUED, ExportStatus.RUNNING, ExportStatus.COMPLETED -> continue
                // A retry replaces the stale failure record for the same file.
                ExportStatus.FAILED -> removeAll(listOf(existing))
                null -> Unit
            }
            val target = batch ?: createBatch().also { batch = it }
            val time = now()
            val job = ExportJob(
                batchId = target.id,
                sourceId = request.sourceId,
                displayName = request.displayName,
                kind = request.kind,
                sourceSizeBytes = request.sizeBytes,
                createdAtMillis = time,
                updatedAtMillis = time,
            )
            // The list shows the latest batch only.
            state.value = state.value.filter { it.batchId == target.id } + job.copy(id = persistence.insert(job))
            added++
        }
        added
    }

    /**
     * Collapses a fully finished batch into its summary: when no row is waiting or
     * running, the rows are deleted and the totals kept.
     */
    fun finalizeActiveBatch(): Boolean = synchronized(lock) {
        val batch = activeBatch() ?: return false
        val children = state.value.filter { it.batchId == batch.id }
        if (children.isEmpty() || children.any { it.status.isActive }) return false
        val done = batch.copy(
            status = BatchStatus.DONE,
            totalCount = children.size,
            succeededCount = children.count { it.status == ExportStatus.COMPLETED },
            failedCount = children.count { it.status == ExportStatus.FAILED },
            updatedAtMillis = now(),
        )
        persistence.updateBatch(done)
        batchState.value = batchState.value.map { if (it.id == done.id) done else it }
        removeAll(children)
        pruneBatches()
        true
    }

    fun nextQueued(): ExportJob? = state.value.firstOrNull { it.status == ExportStatus.QUEUED }

    fun hasQueued(): Boolean = nextQueued() != null

    fun markRunning(id: Long) = replace(id) { it.copy(status = ExportStatus.RUNNING, progressPercent = 0, errorMessage = null) }

    fun setProgress(id: Long, percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (find(id)?.let { it.status == ExportStatus.RUNNING && it.progressPercent != clamped } == true) {
            replace(id, persist = false) { it.copy(progressPercent = clamped) }
        }
    }

    fun complete(id: Long, outcome: ExportOutcome, outputUri: String, outputSizeBytes: Long, detail: String?) = replace(id) {
        it.copy(
            status = ExportStatus.COMPLETED, progressPercent = 100, outcome = outcome,
            outputUri = outputUri, outputSizeBytes = outputSizeBytes, detail = detail, errorMessage = null,
        )
    }

    fun fail(id: Long, message: String) = replace(id) { it.copy(status = ExportStatus.FAILED, progressPercent = 0, errorMessage = message) }

    /** Fails every waiting row at once, with one cause, instead of each starting and failing in turn. */
    fun failQueued(message: String): Int = synchronized(lock) {
        val queued = state.value.filter { it.status == ExportStatus.QUEUED }
        queued.forEach { fail(it.id, message) }
        queued.size
    }

    fun requeue(id: Long) = replace(id) { it.copy(status = ExportStatus.QUEUED, progressPercent = 0) }

    /** Deletes a row. A batch left without rows has nothing to show or resume and goes too. */
    fun remove(id: Long): Unit = synchronized(lock) {
        val job = find(id) ?: return
        removeAll(listOf(job))
        val batch = activeBatch() ?: return
        if (batch.id == job.batchId && state.value.none { it.batchId == batch.id }) {
            persistence.deleteBatches(listOf(batch.id))
            batchState.value = batchState.value.filter { it.id != batch.id }
        }
    }

    /** "Delete export history": every batch and every row goes away. */
    fun clearHistory(): Unit = synchronized(lock) {
        removeAll(state.value)
        persistence.deleteBatches(batchState.value.map { it.id })
        batchState.value = emptyList()
    }

    private fun removeAll(jobs: List<ExportJob>) {
        if (jobs.isEmpty()) return
        val ids = jobs.map { it.id }.toSet()
        persistence.delete(ids)
        state.value = state.value.filter { it.id !in ids }
    }

    companion object {
        const val MAX_BATCHES = 2
    }
}
