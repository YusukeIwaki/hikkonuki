package app.hikkonuki.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** Reads camera files. Ids are opaque (a content URI or a path). */
interface SourceAccess {
    fun exists(sourceId: String): Boolean
    fun open(sourceId: String): InputStream
}

data class ProbedVideo(val info: VideoInfo, val containerCreationMillis: Long?, val model: String?)

interface VideoProber {
    fun probe(sourceId: String, camera: CameraKind): ProbedVideo
}

interface VideoTranscoder {
    val name: String

    /** Writes a complete MP4 to [output] and returns a short description of what it made. */
    suspend fun transcode(sourceId: String, spec: TranscodeSpec, output: File, onProgress: (Float) -> Unit): String
}

data class GalleryEntry(val uri: String, val sizeBytes: Long)

/** A file being written into the phone's gallery; invisible to other apps until published. */
interface PendingEntry {
    val file: File
    fun publish(): GalleryEntry
    fun discard()
}

/** The phone-side destination (DCIM/Hikkonuki through MediaStore on a device). */
interface Gallery {
    fun findExisting(displayName: String, kind: MediaKind): GalleryEntry?
    fun create(displayName: String, kind: MediaKind): PendingEntry
    /** Removes half-written files left behind by a killed process. */
    fun discardStale()
    fun availableBytes(): Long
}

/** A failure the engine can name. [message] is technical detail for logs, in English. */
class ExportException(val reason: FailureReason, message: String) : Exception(message)

/** Tries each transcoder in order, so an unsupported fast path degrades instead of failing. */
class FallbackTranscoder(private val candidates: List<VideoTranscoder>) : VideoTranscoder {
    override val name: String get() = candidates.joinToString(" > ") { it.name }

    override suspend fun transcode(sourceId: String, spec: TranscodeSpec, output: File, onProgress: (Float) -> Unit): String {
        var failure: Exception? = null
        for (candidate in candidates) {
            try {
                return candidate.transcode(sourceId, spec, output, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure?.let(e::addSuppressed)
                failure = e
                output.delete()
                onProgress(0f)
            }
        }
        throw failure ?: ExportException(FailureReason.OTHER, "no converter available")
    }
}

/**
 * Turns queued jobs into files in the gallery, one at a time. It knows nothing about
 * screens, services or Android: the UI only reads [ExportQueue.jobs], and whoever hosts
 * the engine calls [drain] whenever there may be work.
 *
 * It is built to always end up with the file imported:
 * - every state change is persisted, and [start] re-queues whatever a killed process left;
 * - output is written to a pending gallery entry, so a half-written file is never visible;
 * - unplugging the camera fails the file in flight and every file still waiting, at once
 *   and with one clear reason, leaving no partial output;
 * - a video that cannot be converted is imported as the original instead of failing.
 *
 * When the queue runs dry, a batch with nothing left to do is collapsed into its summary.
 */
class ExportEngine(
    val queue: ExportQueue,
    private val source: SourceAccess,
    private val prober: VideoProber,
    private val transcoder: VideoTranscoder,
    private val gallery: Gallery,
    private val io: CoroutineDispatcher,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
    private val sourceCheckIntervalMillis: Long = 1000,
) {
    private val drainLock = Mutex()
    private val cancelRequested = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var running: Pair<Long, Job>? = null

    /** Jobs that were running when the camera was reported gone. */
    private val detached = ConcurrentHashMap.newKeySet<Long>()

    enum class Ending { COMPLETED, FAILED, CANCELLED }

    /** The job that most recently stopped being worked on, for "what just happened" notices. */
    @Volatile
    var lastEnded: Pair<ExportJob, Ending>? = null
        private set

    /**
     * Call once per process before the first [drain]. Returns true when there is work.
     *
     * A process that was killed mid-export (no detach broadcast involved) left a row marked
     * as running. If the camera is still attached, that file goes back in line and the
     * export simply continues. If the camera is gone by now, nobody was alive to hear the
     * broadcast, so the disconnect is handled here: the rest of the batch fails with the
     * "USB removed" reason and the batch is closed.
     */
    fun start(): Boolean {
        val interrupted = queue.jobs.value.filter { it.status == ExportStatus.RUNNING }
        queue.recover()
        runCatching { gallery.discardStale() }
        if (interrupted.any { !runCatching { source.exists(it.sourceId) }.getOrDefault(false) }) {
            queue.failQueued(FailureReason.USB_REMOVED)
        }
        // Also covers a process that died between the last file finishing and the summary.
        if (!queue.hasQueued()) queue.finalizeActiveBatch()
        return queue.hasQueued()
    }

    fun enqueue(requests: List<ExportRequest>): Boolean {
        queue.enqueue(requests)
        return queue.hasQueued()
    }

    /**
     * The system reported that the camera was detached or its storage unmounted.
     *
     * Waiting files are failed immediately, and the file in flight is stopped and failed
     * with the same cause, so the batch ends as "N of M succeeded". This is decided by the
     * report itself, not by whether the files are readable right now: after a momentary
     * dropout the camera may already be back, and the export must still not carry on with
     * a half-read file.
     */
    fun sourceDisconnected() {
        queue.failQueued(FailureReason.USB_REMOVED)
        val current = running
        if (current != null) {
            detached += current.first
            current.second.cancel()
        } else {
            queue.finalizeActiveBatch()
        }
    }

    /** User cancellation: the job disappears, and a partially written file with it. */
    fun cancel(jobId: Long) {
        val current = running
        if (current != null && current.first == jobId) {
            cancelRequested += jobId
            current.second.cancel()
        } else {
            queue.remove(jobId)
            // Removing the last unfinished row can complete the batch.
            if (current == null && !queue.hasQueued()) queue.finalizeActiveBatch()
        }
    }

    /** "Delete export history": stops whatever is running and forgets everything. */
    fun clearHistory() {
        queue.jobs.value.filter { it.status.isActive }.forEach { cancel(it.id) }
        queue.clearHistory()
    }

    /** Processes queued jobs until none are left. Concurrent calls wait for the first. */
    suspend fun drain() = drainLock.withLock {
        while (true) {
            val job = queue.nextQueued() ?: break
            runJob(job)
        }
        queue.finalizeActiveBatch()
    }

    private suspend fun runJob(job: ExportJob) {
        queue.markRunning(job.id)
        var result: Result<Done>? = null
        var sourceLost = false
        try {
            coroutineScope {
                val work = launch(io) {
                    result = runCatching { process(job) { queue.setProgress(job.id, (it * 100).toInt().coerceIn(0, 99)) } }
                }
                running = job.id to work
                // Detach broadcasts ([sourceDisconnected]) are the main signal, but they are not
                // guaranteed, and an open stream can keep delivering buffered data after the
                // cable is pulled. So also ask the source itself; two misses in a row count as gone.
                val watchdog = launch {
                    var misses = 0
                    while (true) {
                        delay(sourceCheckIntervalMillis)
                        misses = if (withContext(io) { source.exists(job.sourceId) }) 0 else misses + 1
                        if (misses >= 2) {
                            sourceLost = true
                            queue.failQueued(FailureReason.USB_REMOVED)
                            work.cancel()
                            break
                        }
                    }
                }
                work.join()
                watchdog.cancel()
            }
        } catch (e: CancellationException) {
            // The host is shutting down: leave the job queued for the next start.
            queue.requeue(job.id)
            throw e
        } finally {
            running = null
        }
        val userCancelled = cancelRequested.remove(job.id)
        val wasDetached = detached.remove(job.id)
        val ending = result!!.fold(
            onSuccess = { done ->
                queue.complete(job.id, done.outcome, done.entry.uri, done.entry.sizeBytes, done.detail)
                Ending.COMPLETED
            },
            onFailure = { error ->
                when {
                    userCancelled -> Ending.CANCELLED.also { queue.remove(job.id) }
                    // Which detector fired first must not change the outcome: a detach report,
                    // the watchdog, or simply finding the source gone all mean "USB removed".
                    wasDetached || sourceLost || !runCatching { source.exists(job.sourceId) }.getOrDefault(false) ->
                        Ending.FAILED.also {
                            queue.fail(job.id, FailureReason.USB_REMOVED)
                            queue.failQueued(FailureReason.USB_REMOVED)
                        }
                    error is CancellationException -> Ending.CANCELLED.also { queue.requeue(job.id) }
                    else -> Ending.FAILED.also {
                        queue.fail(
                            job.id, (error as? ExportException)?.reason ?: FailureReason.OTHER,
                            error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                        )
                    }
                }
            },
        )
        lastEnded = job to ending
    }

    private class Done(val outcome: ExportOutcome, val entry: GalleryEntry, val detail: String?)

    private suspend fun process(job: ExportJob, onProgress: (Float) -> Unit): Done {
        if (job.kind == MediaKind.PHOTO) {
            gallery.findExisting(job.displayName, MediaKind.PHOTO)?.let { return Done(ExportOutcome.ALREADY_IMPORTED, it, null) }
            return Done(ExportOutcome.COPIED, copyOriginal(job, null, onProgress), null)
        }
        val slimName = slimName(job.displayName)
        (gallery.findExisting(slimName, MediaKind.VIDEO) ?: gallery.findExisting(job.displayName, MediaKind.VIDEO))
            ?.let { return Done(ExportOutcome.ALREADY_IMPORTED, it, null) }

        val camera = CameraDetector.fromFileName(job.displayName)
        val probed = try {
            prober.probe(job.sourceId, camera)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!source.exists(job.sourceId)) throw e
            null
        }
        val date = CaptureDateResolver.resolve(camera, job.displayName, probed?.containerCreationMillis, zone(), now())
        val plan = probed?.let { SlimPlanner.plan(camera, it.info) } ?: VideoPlan.KeepOriginal(KeepReason.UNREADABLE)

        var keepReason = (plan as? VideoPlan.KeepOriginal)?.reason?.let(::keptDetail)
        if (plan is VideoPlan.Transcode) {
            val spec = plan.spec
            requireSpace(spec.bitrateBps.toLong() * spec.durationMillis / 8000 * 11 / 10)
            val pending = gallery.create(slimName, MediaKind.VIDEO)
            try {
                val description = transcoder.transcode(job.sourceId, spec, pending.file, onProgress)
                currentCoroutineContext().ensureActive()
                if (pending.file.length() <= 0) throw ExportException(FailureReason.EMPTY_OUTPUT, "converter wrote an empty file")
                if (SlimPlanner.savesEnough(job.sourceSizeBytes, pending.file.length())) {
                    date?.let {
                        Mp4Stamper.stamp(
                            pending.file,
                            Mp4Stamper.Stamp(it.epochMillis, it.utcOffsetSeconds, camera.make, probed?.model),
                        )
                    }
                    return Done(ExportOutcome.CONVERTED, pending.publish(), description)
                }
                pending.discard()
                keepReason = keptDetail(KeepReason.LOW_SAVINGS)
            } catch (e: CancellationException) {
                pending.discard()
                throw e
            } catch (e: Exception) {
                pending.discard()
                // Converting failed, but the point is to get the video onto the phone.
                if (!source.exists(job.sourceId)) throw e
                keepReason = keptDetail(KeepReason.CONVERSION_FAILED) + " (${shortReason(e)})"
            }
            onProgress(0f)
        }
        // Originals are left byte-identical unless their own date is missing or wrong.
        val fixDate = date?.takeIf { it.source == DateSource.FILENAME }
        return Done(ExportOutcome.ORIGINAL, copyOriginal(job, fixDate, onProgress), keepReason)
    }

    private fun requireSpace(neededBytes: Long) {
        val available = runCatching { gallery.availableBytes() }.getOrDefault(Long.MAX_VALUE)
        if (available < neededBytes + 64L * 1024 * 1024) {
            throw ExportException(FailureReason.NO_SPACE, "needs $neededBytes bytes, $available available")
        }
    }

    private suspend fun copyOriginal(job: ExportJob, date: CaptureDate?, onProgress: (Float) -> Unit): GalleryEntry {
        requireSpace(job.sourceSizeBytes)
        val pending = gallery.create(job.displayName, job.kind)
        try {
            source.open(job.sourceId).use { input ->
                pending.file.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (job.sourceSizeBytes > 0) onProgress(copied.toFloat() / job.sourceSizeBytes)
                    }
                }
            }
            if (job.sourceSizeBytes > 0 && pending.file.length() != job.sourceSizeBytes) {
                throw ExportException(FailureReason.INCOMPLETE_COPY, "copied ${pending.file.length()} of ${job.sourceSizeBytes} bytes")
            }
            if (date != null && job.kind == MediaKind.VIDEO) {
                runCatching { Mp4Stamper.stamp(pending.file, Mp4Stamper.Stamp(date.epochMillis, date.utcOffsetSeconds), writeTags = false) }
            }
            return pending.publish()
        } catch (e: Throwable) {
            pending.discard()
            throw e
        }
    }

    companion object {
        /** The note stored with an import that kept the original: a record, not UI text. */
        fun keptDetail(reason: KeepReason): String = "original kept: ${reason.name}"

        /** Codec errors can run to a page of format dumps; the list only needs the gist. */
        internal fun shortReason(error: Throwable): String {
            val text = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty().ifEmpty { error.javaClass.simpleName }
            return if (text.length > 60) text.take(60) + "…" else text
        }

        fun slimName(displayName: String): String = displayName.substringBeforeLast('.') + "_slim.mp4"

        /** Names under which [displayName] may already be in the gallery. */
        fun galleryNames(displayName: String, kind: MediaKind): List<String> =
            if (kind == MediaKind.VIDEO) listOf(slimName(displayName), displayName) else listOf(displayName)
    }
}
