package app.hikkonuki.android.platform

import android.content.Context
import app.hikkonuki.android.core.ExportBatch
import app.hikkonuki.android.core.ExportEngine
import app.hikkonuki.android.core.ExportJob
import app.hikkonuki.android.core.ExportQueue
import app.hikkonuki.android.core.ExportRequest
import app.hikkonuki.android.core.FallbackTranscoder
import app.hikkonuki.android.ui.ExportController
import app.hikkonuki.android.ui.MediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** The app's few long-lived objects, wired by hand. */
class AppGraph private constructor(context: Context) {
    private val appContext = context.applicationContext
    val source = AndroidSource(appContext)
    val gallery = MediaStoreGallery(appContext)
    val engine = ExportEngine(
        queue = ExportQueue(SqliteJobPersistence(appContext)),
        source = source,
        prober = AndroidVideoProber(source),
        transcoder = FallbackTranscoder(
            listOf(
                DirectVideoTranscoder(source, tenBit = true),
                DirectVideoTranscoder(source, tenBit = false),
                Media3VideoTranscoder(appContext, source),
            ),
        ),
        gallery = gallery,
        io = Dispatchers.IO,
    )
    val library: MediaLibrary = UsbMediaLibrary(appContext, source)

    /** True when a previous process left unfinished exports behind. */
    private val hadPendingWork: Boolean = engine.start()

    val exports: ExportController = object : ExportController {
        override val jobs: StateFlow<List<ExportJob>> get() = engine.queue.jobs
        override val batches: StateFlow<List<ExportBatch>> get() = engine.queue.batches
        private fun run(hasWork: Boolean) { if (hasWork) ExportService.start(appContext) }
        override fun export(requests: List<ExportRequest>) = run(engine.enqueue(requests))
        override fun resume() = run(hadPendingWork && engine.queue.hasQueued())
        override fun cancel(jobId: Long) = engine.cancel(jobId)
        override fun clearHistory() = engine.clearHistory()
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context).also { instance = it }
        }
    }
}
