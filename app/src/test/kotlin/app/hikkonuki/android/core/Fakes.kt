package app.hikkonuki.android.core

import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class MemoryPersistence : JobPersistence {
    val rows = LinkedHashMap<Long, ExportJob>()
    val batches = LinkedHashMap<Long, ExportBatch>()

    /** Finished rows outlive their deletion here, so tests can see how each file ended. */
    val archive = LinkedHashMap<Long, ExportJob>()
    private var nextId = 1L
    private var nextBatchId = 1L

    /** Every row that finished or still exists, in creation order. */
    val all: List<ExportJob> get() = (archive + rows).values.sortedBy { it.id }

    override fun loadJobs(): List<ExportJob> = rows.values.toList()
    override fun insert(job: ExportJob): Long = nextId++.also { rows[it] = job.copy(id = it) }
    override fun update(job: ExportJob) { rows[job.id] = job }
    override fun delete(ids: Collection<Long>) {
        for (id in ids) {
            val row = rows.remove(id) ?: continue
            if (row.status == ExportStatus.COMPLETED || row.status == ExportStatus.FAILED) archive[id] = row
        }
    }
    override fun loadBatches(): List<ExportBatch> = batches.values.toList()
    override fun insertBatch(batch: ExportBatch): Long = nextBatchId++.also { batches[it] = batch.copy(id = it) }
    override fun updateBatch(batch: ExportBatch) { batches[batch.id] = batch }
    override fun deleteBatches(ids: Collection<Long>) { ids.forEach(batches::remove) }
}

class FakeSource : SourceAccess {
    val files = HashMap<String, ByteArray>()
    var plugged = true
    var failReads = false
    override fun exists(sourceId: String): Boolean = plugged && sourceId in files
    override fun open(sourceId: String): InputStream {
        if (!exists(sourceId)) throw FileNotFoundException(sourceId)
        if (failReads) throw IOException("read error")
        return ByteArrayInputStream(files.getValue(sourceId))
    }
}

class FakeProber(private val source: FakeSource) : VideoProber {
    val videos = HashMap<String, ProbedVideo>()
    override fun probe(sourceId: String, camera: CameraKind): ProbedVideo {
        if (!source.exists(sourceId)) throw FileNotFoundException(sourceId)
        return videos[sourceId] ?: throw IOException("not a video")
    }
}

class FakeTranscoder(override val name: String = "fake", private val output: ByteArray) : VideoTranscoder {
    var durationMillis = 10_000L
    var failure: Exception? = null
    val calls = ArrayList<Pair<String, TranscodeSpec>>()

    override suspend fun transcode(sourceId: String, spec: TranscodeSpec, output: File, onProgress: (Float) -> Unit): String {
        calls += sourceId to spec
        output.writeBytes(ByteArray(10))
        repeat(10) { step ->
            delay(durationMillis / 10)
            onProgress((step + 1) / 10f)
        }
        failure?.let { throw it }
        output.writeBytes(this.output)
        return "$name ${spec.width}x${spec.height}"
    }
}

class FakeGallery(private val dir: File) : Gallery {
    val published = LinkedHashMap<String, File>()
    val pendingFiles: List<File> get() = dir.listFiles { file -> file.name.startsWith(".pending-") }!!.toList()
    var free = Long.MAX_VALUE
    private var counter = 0

    override fun findExisting(displayName: String, kind: MediaKind): GalleryEntry? =
        published[displayName]?.let { GalleryEntry("gallery://$displayName", it.length()) }

    override fun create(displayName: String, kind: MediaKind): PendingEntry {
        val file = File(dir, ".pending-${counter++}-$displayName")
        return object : PendingEntry {
            override val file: File = file
            override fun publish(): GalleryEntry {
                val target = File(dir, displayName)
                check(file.renameTo(target))
                published[displayName] = target
                return GalleryEntry("gallery://$displayName", target.length())
            }
            override fun discard() { file.delete() }
        }
    }

    override fun discardStale() { pendingFiles.forEach { it.delete() } }
    override fun availableBytes(): Long = free
}
