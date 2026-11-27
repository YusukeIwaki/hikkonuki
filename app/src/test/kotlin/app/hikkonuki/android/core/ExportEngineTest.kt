package app.hikkonuki.android.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ExportEngineTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = TestScope(dispatcher)

    private val persistence = MemoryPersistence()
    private val source = FakeSource()
    private val prober = FakeProber(source)
    private val encoded = javaClass.getResourceAsStream("/fixtures/tiny_moov_last.mp4")!!.readBytes()
    private val transcoder = FakeTranscoder(output = encoded)
    private val gallery by lazy { FakeGallery(temp.newFolder("gallery")) }
    private val now = Instant.parse("2026-11-27T00:00:00Z").toEpochMilli()

    private fun engine(transcoder: VideoTranscoder = this.transcoder) = ExportEngine(
        queue = ExportQueue(persistence) { now },
        source = source, prober = prober, transcoder = transcoder, gallery = gallery,
        io = dispatcher, zone = { ZoneId.of("Asia/Tokyo") }, now = { now },
    )

    private fun utc(text: String) = Instant.parse(text).toEpochMilli()
    private fun metadata(file: File) = RandomAccessFile(file, "r").use { Mp4.readMetadata(FileSource(it)) }

    private val dji4k = VideoInfo(
        width = 3840, height = 2160, fps = 29.97, durationMillis = 41_608,
        videoBitrateBps = 38_000_000, audioBitrateBps = 317_000, tenBit = true,
    )

    /** A camera video whose bytes are an MP4 with the given container date. */
    private fun video(name: String, containerTime: String? = "2026-11-07T02:34:03Z", info: VideoInfo = dji4k, size: Int = 200_000): ExportRequest {
        val id = "usb://$name"
        val file = temp.newFile()
        file.writeBytes(encoded + ByteArray(0))
        Mp4Stamper.stamp(file, Mp4Stamper.Stamp(containerTime?.let(::utc) ?: (-Mp4.QT_EPOCH_OFFSET_SECONDS * 1000)), writeTags = false)
        // Pad inside a trailing box so the "camera original" has a realistic size.
        val padding = size - file.length().toInt()
        val bytes = file.readBytes() + java.nio.ByteBuffer.allocate(padding).putInt(padding).put("free".toByteArray()).array()
        source.files[id] = bytes
        prober.videos[id] = ProbedVideo(info, containerTime?.let(::utc), if (name.startsWith("DJI")) "OsmoPocket4P" else "Insta360 Ace Pro 2")
        return ExportRequest(id, name, MediaKind.VIDEO, bytes.size.toLong())
    }

    private fun photo(name: String): ExportRequest {
        val id = "usb://$name"
        source.files[id] = ByteArray(5000) { (it % 251).toByte() }
        return ExportRequest(id, name, MediaKind.PHOTO, 5000)
    }

    /** How every file ended. A finished batch keeps only its summary, so ask the store. */
    @Suppress("UNUSED_PARAMETER")
    private fun jobs(engine: ExportEngine) = persistence.all
    private fun batch(engine: ExportEngine) = engine.queue.batches.value.first()

    @Test
    fun photoIsCopiedByteForByte() = scope.runTest {
        val engine = engine()
        val request = photo("DJI_20261107113403_0001_D.JPG")
        engine.enqueue(listOf(request))
        engine.drain()
        val job = jobs(engine).single()
        assertEquals(ExportStatus.COMPLETED, job.status)
        assertEquals(ExportOutcome.COPIED, job.outcome)
        assertEquals(5000, job.outputSizeBytes)
        assertArrayEquals(source.files.getValue(request.sourceId), gallery.published.getValue(request.displayName).readBytes())
        assertTrue(gallery.pendingFiles.isEmpty())
    }

    @Test
    fun osmoPocketVideoIsConvertedAndStampedWithItsCaptureDate() = scope.runTest {
        val engine = engine()
        engine.enqueue(listOf(video("DJI_20261107113403_0005_D.MP4")))
        engine.drain()
        val job = jobs(engine).single()
        assertEquals(ExportStatus.COMPLETED, job.status)
        assertEquals(ExportOutcome.CONVERTED, job.outcome)
        assertEquals("fake 2560x1440", job.detail)
        val spec = transcoder.calls.single().second
        assertEquals(2560 to 1440, spec.width to spec.height)
        assertTrue(spec.tenBit)
        val output = gallery.published.getValue("DJI_20261107113403_0005_D_slim.mp4")
        assertEquals(job.outputSizeBytes, output.length())
        val meta = metadata(output)
        assertEquals(utc("2026-11-07T02:34:03Z"), meta.creationTimeMillis)
        assertEquals("2026-11-07T11:34:03+0900", meta.tags["com.apple.quicktime.creationdate"])
        assertEquals("DJI", meta.tags["com.apple.quicktime.make"])
        assertEquals("OsmoPocket4P", meta.tags["com.apple.quicktime.model"])
    }

    @Test
    fun insta360VideoGetsItsDateFromTheFileNameWhenTheContainerHasNone() = scope.runTest {
        val engine = engine()
        val info = VideoInfo(width = 1920, height = 1080, fps = 29.97, durationMillis = 52_000, videoBitrateBps = 52_400_000, audioBitrateBps = 189_000)
        engine.enqueue(listOf(video("VID_20260510_132733_019.mp4", containerTime = null, info = info)))
        engine.drain()
        assertEquals(ExportOutcome.CONVERTED, jobs(engine).single().outcome)
        val spec = transcoder.calls.single().second
        assertEquals(Triple(1920, 1080, 8_000_000), Triple(spec.width, spec.height, spec.bitrateBps))
        val meta = metadata(gallery.published.getValue("VID_20260510_132733_019_slim.mp4"))
        assertEquals(utc("2026-05-10T04:27:33Z"), meta.creationTimeMillis)
        assertEquals("2026-05-10T13:27:33+0900", meta.tags["com.apple.quicktime.creationdate"])
        assertEquals("Insta360", meta.tags["com.android.manufacturer"])
        assertEquals("Insta360 Ace Pro 2", meta.tags["com.android.model"])
    }

    @Test
    fun progressIsReportedWhileRunning() = scope.runTest {
        val engine = engine()
        engine.enqueue(listOf(video("DJI_20261107113403_0005_D.MP4")))
        val draining = launch { engine.drain() }
        advanceTimeBy(5_500)
        val job = engine.queue.jobs.value.single()
        assertEquals(ExportStatus.RUNNING, job.status)
        assertEquals(50, job.progressPercent)
        assertEquals(BatchStatus.ACTIVE, batch(engine).status)
        draining.join()
        assertEquals(100, jobs(engine).single().progressPercent)
    }

    @Test
    fun alreadyImportedFilesAreNotConvertedAgain() = scope.runTest {
        val first = engine()
        val requests = listOf(video("DJI_20261107113403_0005_D.MP4"), photo("DJI_20261107113403_0001_D.JPG"))
        first.enqueue(requests)
        first.drain()
        assertEquals(1, transcoder.calls.size)
        // History was cleared (or the app reinstalled) and the user selects everything again.
        persistence.rows.clear()
        persistence.archive.clear()
        persistence.batches.clear()
        val second = engine()
        second.enqueue(requests)
        second.drain()
        assertEquals(1, transcoder.calls.size)
        assertEquals(listOf(ExportOutcome.ALREADY_IMPORTED, ExportOutcome.ALREADY_IMPORTED), jobs(second).map { it.outcome })
        assertEquals(2, gallery.published.size)
    }

    @Test
    fun hdrVideoIsImportedUntouched() = scope.runTest {
        val engine = engine()
        val request = video("DJI_20261107113403_0005_D.MP4", info = dji4k.copy(hdr = true))
        engine.enqueue(listOf(request))
        engine.drain()
        val job = jobs(engine).single()
        assertEquals(ExportOutcome.ORIGINAL, job.outcome)
        assertEquals(ExportEngine.keptDetail(KeepReason.HDR), job.detail)
        assertTrue(transcoder.calls.isEmpty())
        assertArrayEquals(source.files.getValue(request.sourceId), gallery.published.getValue(request.displayName).readBytes())
    }

    @Test
    fun originalWithoutADateGetsOneFromItsFileName() = scope.runTest {
        val engine = engine()
        val request = video("VID_20260510_132733_019.mp4", containerTime = null, info = dji4k.copy(hdr = true))
        engine.enqueue(listOf(request))
        engine.drain()
        val output = gallery.published.getValue(request.displayName)
        assertEquals(request.sizeBytes, output.length())
        assertEquals(utc("2026-05-10T04:27:33Z"), metadata(output).creationTimeMillis)
    }

    @Test
    fun conversionThatDoesNotSaveEnoughFallsBackToTheOriginal() = scope.runTest {
        val engine = engine()
        val request = video("DJI_20261107113403_0005_D.MP4", size = encoded.size + 100)
        engine.enqueue(listOf(request))
        engine.drain()
        val job = jobs(engine).single()
        assertEquals(ExportOutcome.ORIGINAL, job.outcome)
        assertEquals(ExportEngine.keptDetail(KeepReason.LOW_SAVINGS), job.detail)
        assertEquals(setOf(request.displayName), gallery.published.keys)
        assertTrue(gallery.pendingFiles.isEmpty())
    }

    @Test
    fun videoThatCannotBeConvertedIsStillImported() = scope.runTest {
        val engine = engine()
        transcoder.failure = IllegalStateException("codec exploded")
        val request = video("DJI_20261107113403_0005_D.MP4")
        engine.enqueue(listOf(request))
        engine.drain()
        val job = jobs(engine).single()
        assertEquals(ExportStatus.COMPLETED, job.status)
        assertEquals(ExportOutcome.ORIGINAL, job.outcome)
        assertTrue(job.detail!!.contains("codec exploded"))
        assertArrayEquals(source.files.getValue(request.sourceId), gallery.published.getValue(request.displayName).readBytes())
        assertTrue(gallery.pendingFiles.isEmpty())
    }

    @Test
    fun unreadableVideoIsImportedAsIs() = scope.runTest {
        val engine = engine()
        val request = video("VID_20260510_132733_019.mp4")
        prober.videos.clear()
        engine.enqueue(listOf(request))
        engine.drain()
        val job = jobs(engine).single()
        assertEquals(ExportOutcome.ORIGINAL, job.outcome)
        assertEquals(ExportEngine.keptDetail(KeepReason.UNREADABLE), job.detail)
    }

    @Test
    fun fallbackTranscoderMovesOnToTheNextEngine() = scope.runTest {
        val broken = FakeTranscoder("tenbit", encoded).apply { failure = IllegalStateException("no Main10") }
        val working = FakeTranscoder("eightbit", encoded)
        val engine = engine(FallbackTranscoder(listOf(broken, working)))
        engine.enqueue(listOf(video("DJI_20261107113403_0005_D.MP4")))
        engine.drain()
        assertEquals("eightbit 2560x1440", jobs(engine).single().detail)
        assertEquals(1, broken.calls.size)
        assertEquals(1, working.calls.size)
    }

    @Test
    fun fallbackTranscoderReportsTheLastFailure() = scope.runTest {
        val a = FakeTranscoder("a", encoded).apply { failure = IllegalStateException("first") }
        val b = FakeTranscoder("b", encoded).apply { failure = IllegalStateException("second") }
        val engine = engine(FallbackTranscoder(listOf(a, b)))
        engine.enqueue(listOf(video("DJI_20261107113403_0005_D.MP4")))
        engine.drain()
        assertTrue(jobs(engine).single().detail!!.contains("second"))
    }

    @Test
    fun cancellingTheRunningJobRemovesItAndLeavesNoFiles() = scope.runTest {
        val engine = engine()
        engine.enqueue(listOf(video("DJI_20261107113403_0005_D.MP4"), photo("DJI_20261107113403_0001_D.JPG")))
        val draining = launch { engine.drain() }
        advanceTimeBy(3_000)
        val running = engine.queue.jobs.value.first()
        assertEquals(ExportStatus.RUNNING, running.status)
        assertEquals(1, gallery.pendingFiles.size)
        engine.cancel(running.id)
        draining.join()
        // The cancelled job is gone without a trace; the next one still ran.
        assertEquals(listOf("DJI_20261107113403_0001_D.JPG"), jobs(engine).map { it.displayName })
        assertEquals(ExportStatus.COMPLETED, jobs(engine).single().status)
        assertEquals(setOf("DJI_20261107113403_0001_D.JPG"), gallery.published.keys)
        assertTrue(gallery.pendingFiles.isEmpty())
        assertEquals(Triple(BatchStatus.DONE, 1, 1), batch(engine).let { Triple(it.status, it.totalCount, it.succeededCount) })
        assertEquals(ExportEngine.Ending.COMPLETED, engine.lastEnded!!.second)
    }

    @Test
    fun cancellingAQueuedJobRemovesItBeforeItStarts() = scope.runTest {
        val engine = engine()
        engine.enqueue(listOf(video("DJI_20261107113403_0005_D.MP4"), video("DJI_20261107114219_0006_D.MP4")))
        val draining = launch { engine.drain() }
        advanceTimeBy(1_000)
        engine.cancel(engine.queue.jobs.value[1].id)
        draining.join()
        assertEquals(1, transcoder.calls.size)
        assertEquals(listOf(ExportStatus.COMPLETED), jobs(engine).map { it.status })
    }

    @Test
    fun clearingHistoryStopsEverythingAndForgetsIt() = scope.runTest {
        val engine = engine()
        engine.enqueue(listOf(photo("a.jpg"), video("DJI_20261107113403_0005_D.MP4"), video("DJI_20261107114219_0006_D.MP4")))
        val draining = launch { engine.drain() }
        advanceTimeBy(1_000)
        engine.clearHistory()
        draining.join()
        assertTrue(engine.queue.jobs.value.isEmpty())
        assertTrue(engine.queue.batches.value.isEmpty())
        assertTrue(persistence.rows.isEmpty())
        assertTrue(persistence.batches.isEmpty())
        // What had already been imported stays imported; nothing half-written remains.
        assertEquals(setOf("a.jpg"), gallery.published.keys)
        assertTrue(gallery.pendingFiles.isEmpty())
    }

    @Test
    fun finishedBatchCollapsesIntoItsSummary() = scope.runTest {
        val engine = engine()
        // The photo fits; the "video" original (kept because HDR) does not.
        engine.enqueue(listOf(photo("a.jpg"), video("DJI_20261107113403_0005_D.MP4", info = dji4k.copy(hdr = true), size = 400_000)))
        gallery.free = 64L * 1024 * 1024 + 100_000
        engine.drain()
        assertTrue(engine.queue.jobs.value.isEmpty())
        val summary = batch(engine)
        assertEquals(BatchStatus.DONE, summary.status)
        assertEquals(Triple(2, 1, 1), Triple(summary.totalCount, summary.succeededCount, summary.failedCount))
        assertEquals(now, summary.updatedAtMillis)

        // The next export is a new batch; the finished one stays as history until a third.
        gallery.free = Long.MAX_VALUE
        engine.enqueue(listOf(photo("b.jpg")))
        assertEquals(listOf(BatchStatus.ACTIVE, BatchStatus.DONE), engine.queue.batches.value.map { it.status })
        assertEquals(listOf("b.jpg"), engine.queue.jobs.value.map { it.displayName })
        engine.drain()
        engine.enqueue(listOf(photo("c.jpg")))
        engine.drain()
        assertEquals(2, engine.queue.batches.value.size)
        assertEquals(2, persistence.batches.size)
    }

    @Test
    fun cancellingTheLastWaitingRowCompletesTheBatch() = scope.runTest {
        val engine = engine()
        engine.enqueue(listOf(photo("a.jpg"), video("DJI_20261107113403_0005_D.MP4"), photo("b.jpg")))
        val draining = launch { engine.drain() }
        advanceTimeBy(3_000)
        engine.cancel(engine.queue.jobs.value[2].id)
        draining.join()
        assertEquals(Triple(BatchStatus.DONE, 2, 2), batch(engine).let { Triple(it.status, it.totalCount, it.succeededCount) })
    }

    // ---- The camera is unplugged in the middle of an export ----

    private val usbRemoved = FailureReason.USB_REMOVED

    private fun fourFiles() = listOf(
        photo("a.jpg"),
        video("DJI_20261107113403_0005_D.MP4"),
        video("DJI_20261107114219_0006_D.MP4"),
        photo("b.jpg"),
    )

    /** Two imported, the third and everything after it failed, and the batch is over. */
    private fun assertEndedTwoOfFour(engine: ExportEngine) {
        assertEquals(
            listOf(ExportStatus.COMPLETED, ExportStatus.COMPLETED, ExportStatus.FAILED, ExportStatus.FAILED),
            jobs(engine).map { it.status },
        )
        assertEquals(listOf(null, null, usbRemoved, usbRemoved), jobs(engine).map { it.failure })
        val summary = batch(engine)
        assertEquals(BatchStatus.DONE, summary.status)
        assertEquals(Triple(4, 2, 2), Triple(summary.totalCount, summary.succeededCount, summary.failedCount))
        // Nothing is left running, queued, or half-written, in memory or on disk.
        assertTrue(engine.queue.jobs.value.isEmpty())
        assertTrue(persistence.rows.isEmpty())
        assertFalse(engine.queue.hasQueued())
        assertTrue(gallery.pendingFiles.isEmpty())
        assertEquals(setOf("a.jpg", "DJI_20261107113403_0005_D_slim.mp4"), gallery.published.keys)
        assertEquals(ExportEngine.Ending.FAILED, engine.lastEnded!!.second)
        assertEquals("DJI_20261107114219_0006_D.MP4", engine.lastEnded!!.first.displayName)
    }

    @Test
    fun unpluggedDuringTheThirdOfFourEndsTheExportAsTwoOfFour() = scope.runTest {
        val engine = engine()
        engine.enqueue(fourFiles())
        val draining = launch { engine.drain() }
        // a.jpg is instant, the first video takes 10 s, so 14 s is inside the second video.
        advanceTimeBy(14_000)
        assertEquals("DJI_20261107114219_0006_D.MP4", engine.queue.jobs.value.single { it.status == ExportStatus.RUNNING }.displayName)
        assertEquals(1, gallery.pendingFiles.size)
        source.plugged = false
        draining.join()
        assertEndedTwoOfFour(engine)
        // Only the two finished videos were ever handed to the encoder.
        assertEquals(2, transcoder.calls.size)
    }

    @Test
    fun unpluggedDuringTheThirdOfFourWhenTheDecoderNoticesFirst() = scope.runTest {
        // Here the read error reaches the transcoder before the watchdog's second check.
        transcoder.source = source
        val engine = engine()
        engine.enqueue(fourFiles())
        val draining = launch { engine.drain() }
        advanceTimeBy(14_000)
        source.plugged = false
        draining.join()
        assertEndedTwoOfFour(engine)
    }

    @Test
    fun unpluggedWhileAPhotoIsBeingCopiedEndsTheExportThere() = scope.runTest {
        val engine = engine()
        val requests = listOf(photo("a.jpg"), photo("b.jpg"), photo("c.jpg"), photo("d.jpg"))
        engine.enqueue(requests)
        var reads = 0
        // Every photo is 5 chunks plus the end-of-file read; pull the cable inside the third.
        source.onRead = { if (++reads == 15) source.plugged = false }
        engine.drain()
        assertEquals(
            listOf(ExportStatus.COMPLETED, ExportStatus.COMPLETED, ExportStatus.FAILED, ExportStatus.FAILED),
            jobs(engine).map { it.status },
        )
        assertEquals(listOf(usbRemoved, usbRemoved), jobs(engine).drop(2).map { it.failure })
        assertEquals(Triple(4, 2, 2), batch(engine).let { Triple(it.totalCount, it.succeededCount, it.failedCount) })
        assertEquals(BatchStatus.DONE, batch(engine).status)
        assertEquals(setOf("a.jpg", "b.jpg"), gallery.published.keys)
        assertTrue(gallery.pendingFiles.isEmpty())
    }

    @Test
    fun unpluggedBetweenFilesFailsTheRestWithoutStartingThem() = scope.runTest {
        val engine = engine()
        engine.enqueue(fourFiles())
        // The cable goes the instant the second file's conversion is complete: that file is
        // already safe on the phone, and the third must fail before any work is done on it.
        transcoder.onDone = { source.plugged = false }
        engine.drain()
        assertEndedTwoOfFour(engine)
        assertEquals(1, transcoder.calls.size)
    }

    @Test
    fun unpluggedBeforeAnythingStartedFailsEverything() = scope.runTest {
        val engine = engine()
        engine.enqueue(fourFiles())
        source.plugged = false
        engine.drain()
        assertEquals(List(4) { ExportStatus.FAILED }, jobs(engine).map { it.status })
        assertEquals(List(4) { usbRemoved }, jobs(engine).map { it.failure })
        assertEquals(Triple(4, 0, 4), batch(engine).let { Triple(it.totalCount, it.succeededCount, it.failedCount) })
        assertTrue(gallery.published.isEmpty())
        assertTrue(transcoder.calls.isEmpty())
    }

    @Test
    fun momentaryDropoutStillEndsTheExportEvenThoughTheCameraIsBackSecondsLater() = scope.runTest {
        val engine = engine()
        engine.enqueue(fourFiles())
        val draining = launch { engine.drain() }
        advanceTimeBy(14_000)
        source.plugged = false
        // Back after 3 seconds: long enough to be noticed, far too short to finish anything.
        advanceTimeBy(3_000)
        source.plugged = true
        draining.join()
        assertEndedTwoOfFour(engine)
        advanceTimeBy(60_000)
        // Nothing restarts by itself once the batch has ended.
        assertEquals(2, transcoder.calls.size)
        assertEquals(BatchStatus.DONE, batch(engine).status)
    }

    @Test
    fun dropoutTooShortToNoticeDoesNotDisturbTheExport() = scope.runTest {
        val engine = engine()
        engine.enqueue(fourFiles())
        val draining = launch { engine.drain() }
        advanceTimeBy(14_100)
        // Gone for less than one watchdog interval and no read happened to hit it.
        source.plugged = false
        advanceTimeBy(500)
        source.plugged = true
        draining.join()
        assertEquals(List(4) { ExportStatus.COMPLETED }, jobs(engine).map { it.status })
        assertEquals(Triple(4, 4, 0), batch(engine).let { Triple(it.totalCount, it.succeededCount, it.failedCount) })
    }

    // ---- Detach reported by the system (USB_DEVICE_DETACHED / MEDIA_* broadcasts) ----

    @Test
    fun detachReportDuringTheThirdOfFourEndsTheExportAsTwoOfFour() = scope.runTest {
        val engine = engine()
        engine.enqueue(fourFiles())
        val draining = launch { engine.drain() }
        advanceTimeBy(14_000)
        source.plugged = false
        engine.sourceDisconnected()
        // The waiting file fails at once, before the running one has even stopped.
        assertEquals(ExportStatus.FAILED, engine.queue.jobs.value[3].status)
        draining.join()
        assertEndedTwoOfFour(engine)
        // It did not take the watchdog's two seconds.
        assertEquals(14_000, testScheduler.currentTime)
    }

    @Test
    fun detachReportEndsTheExportEvenIfTheCameraIsAlreadyReadableAgain() = scope.runTest {
        // A dropout so short that every file is readable again by the time anyone looks.
        // The read in flight was still interrupted, so the report alone must decide.
        val engine = engine()
        engine.enqueue(fourFiles())
        val draining = launch { engine.drain() }
        advanceTimeBy(14_000)
        engine.sourceDisconnected()
        draining.join()
        assertTrue(source.plugged)
        assertEndedTwoOfFour(engine)
        advanceTimeBy(60_000)
        assertEquals(2, transcoder.calls.size)
    }

    @Test
    fun detachReportWhileAPhotoIsBeingCopied() = scope.runTest {
        val engine = engine()
        engine.enqueue(listOf(photo("a.jpg"), photo("b.jpg"), photo("c.jpg"), photo("d.jpg")))
        var reads = 0
        source.onRead = { if (++reads == 15) engine.sourceDisconnected() }
        engine.drain()
        assertEquals(
            listOf(ExportStatus.COMPLETED, ExportStatus.COMPLETED, ExportStatus.FAILED, ExportStatus.FAILED),
            jobs(engine).map { it.status },
        )
        assertEquals(listOf(usbRemoved, usbRemoved), jobs(engine).drop(2).map { it.failure })
        assertEquals(Triple(4, 2, 2), batch(engine).let { Triple(it.totalCount, it.succeededCount, it.failedCount) })
        assertEquals(setOf("a.jpg", "b.jpg"), gallery.published.keys)
        assertTrue(gallery.pendingFiles.isEmpty())
    }

    @Test
    fun repeatedDetachReportsAreHarmless() = scope.runTest {
        // One cable pull produces several broadcasts (device detached, eject, bad removal).
        val engine = engine()
        engine.enqueue(fourFiles())
        val draining = launch { engine.drain() }
        advanceTimeBy(14_000)
        source.plugged = false
        repeat(3) { engine.sourceDisconnected() }
        draining.join()
        engine.sourceDisconnected()
        assertEndedTwoOfFour(engine)
    }

    @Test
    fun detachReportWithNothingRunningFailsWhatIsWaitingAndClosesTheBatch() = scope.runTest {
        val engine = engine()
        engine.enqueue(listOf(photo("a.jpg"), photo("b.jpg")))
        engine.sourceDisconnected()
        assertEquals(List(2) { ExportStatus.FAILED }, jobs(engine).map { it.status })
        assertEquals(Triple(BatchStatus.DONE, 2, 2), batch(engine).let { Triple(it.status, it.totalCount, it.failedCount) })
        engine.drain()
        assertTrue(gallery.published.isEmpty())
    }

    @Test
    fun detachReportWithNoExportAtAllDoesNothing() {
        val engine = engine()
        engine.sourceDisconnected()
        assertTrue(engine.queue.batches.value.isEmpty())
        assertNull(engine.lastEnded)
    }

    @Test
    fun aDetachReportDoesNotLeakIntoTheNextExport() = scope.runTest {
        val engine = engine()
        val requests = fourFiles()
        engine.enqueue(requests)
        val draining = launch { engine.drain() }
        advanceTimeBy(14_000)
        engine.sourceDisconnected()
        draining.join()
        // Same camera, same files, straight away: everything goes through this time.
        engine.enqueue(requests)
        engine.drain()
        assertEquals(Triple(4, 4, 0), batch(engine).let { Triple(it.totalCount, it.succeededCount, it.failedCount) })
        assertEquals(4, gallery.published.size)
    }

    @Test
    fun aUserCancelIsStillACancelWhenADetachReportFollows() = scope.runTest {
        val engine = engine()
        engine.enqueue(listOf(video("DJI_20261107113403_0005_D.MP4")))
        val draining = launch { engine.drain() }
        advanceTimeBy(3_000)
        engine.cancel(engine.queue.jobs.value.single().id)
        engine.sourceDisconnected()
        draining.join()
        assertTrue(engine.queue.batches.value.isEmpty())
        assertTrue(persistence.all.isEmpty())
    }

    @Test
    fun afterReconnectingTheSameSelectionFinishesInANewBatch() = scope.runTest {
        val engine = engine()
        val requests = fourFiles()
        engine.enqueue(requests)
        val draining = launch { engine.drain() }
        advanceTimeBy(14_000)
        source.plugged = false
        draining.join()
        assertEndedTwoOfFour(engine)

        // Replugged: the volume may come back under different ids.
        source.plugged = true
        val remounted = requests.map { it.copy(sourceId = it.sourceId.replace("usb://", "usb2://")) }
        for ((old, new) in requests.zip(remounted)) {
            source.files[new.sourceId] = source.files.remove(old.sourceId)!!
            prober.videos.remove(old.sourceId)?.let { prober.videos[new.sourceId] = it }
        }
        assertTrue(engine.enqueue(remounted))
        assertEquals(listOf(BatchStatus.ACTIVE, BatchStatus.DONE), engine.queue.batches.value.map { it.status })
        engine.drain()
        // The two that had made it are recognised instead of being converted again.
        assertEquals(
            listOf(ExportOutcome.ALREADY_IMPORTED, ExportOutcome.ALREADY_IMPORTED, ExportOutcome.CONVERTED, ExportOutcome.COPIED),
            jobs(engine).takeLast(4).map { it.outcome },
        )
        assertEquals(Triple(4, 4, 0), batch(engine).let { Triple(it.totalCount, it.succeededCount, it.failedCount) })
        assertEquals(3, transcoder.calls.size)
        assertEquals(4, gallery.published.size)
    }

    @Test
    fun unpluggedStateSurvivesARestartAsAFinishedBatch() = scope.runTest {
        val first = engine()
        first.enqueue(fourFiles())
        val draining = launch { first.drain() }
        advanceTimeBy(14_000)
        source.plugged = false
        draining.join()
        // A fresh process reading the same store sees a closed batch and nothing to do.
        val second = engine()
        assertFalse(second.start())
        assertTrue(second.queue.jobs.value.isEmpty())
        assertEquals(Triple(4, 2, 2), batch(second).let { Triple(it.totalCount, it.succeededCount, it.failedCount) })
    }

    @Test
    fun stoppingTheHostKeepsTheJobQueuedForNextTime() = scope.runTest {
        val first = engine()
        val request = video("DJI_20261107113403_0005_D.MP4")
        first.enqueue(listOf(request))
        val draining = launch { first.drain() }
        advanceTimeBy(4_000)
        draining.cancel()
        draining.join()
        assertEquals(ExportStatus.QUEUED, persistence.rows.values.single().status)
        assertTrue(gallery.pendingFiles.isEmpty())
        assertTrue(gallery.published.isEmpty())

        val second = engine()
        assertTrue(second.start())
        second.drain()
        assertEquals(ExportStatus.COMPLETED, jobs(second).single().status)
        assertEquals(setOf("DJI_20261107113403_0005_D_slim.mp4"), gallery.published.keys)
    }

    @Test
    fun aKilledProcessIsPickedUpWhereItLeftOff() = scope.runTest {
        val first = engine()
        first.enqueue(listOf(photo("a.jpg"), video("DJI_20261107113403_0005_D.MP4"), photo("b.jpg")))
        val draining = launch { first.drain() }
        advanceTimeBy(4_000)
        // Simulate SIGKILL: nothing gets to clean up. The row stays RUNNING on disk and the
        // half-written gallery file stays behind.
        val snapshot = persistence.rows.toMap()
        val archived = persistence.archive.toMap()
        val orphan = gallery.pendingFiles.single()
        val orphanBytes = orphan.readBytes()
        draining.cancel()
        draining.join()
        persistence.rows.clear()
        persistence.rows.putAll(snapshot)
        persistence.archive.clear()
        persistence.archive.putAll(archived)
        orphan.writeBytes(orphanBytes)
        assertEquals(ExportStatus.RUNNING, persistence.rows.values.toList()[1].status)

        val second = engine()
        assertTrue(second.start())
        assertTrue(gallery.pendingFiles.isEmpty())
        second.drain()
        assertEquals(List(3) { ExportStatus.COMPLETED }, jobs(second).map { it.status })
        assertEquals(setOf("a.jpg", "DJI_20261107113403_0005_D_slim.mp4", "b.jpg"), gallery.published.keys)
    }

    // ---- The app process is killed (no broadcast), then started again ----

    /** Runs [files] until [atMillis], then kills the process: no cleanup code gets to run. */
    private suspend fun kotlinx.coroutines.test.TestScope.killedAt(atMillis: Long, files: List<ExportRequest>) {
        val first = engine()
        first.enqueue(files)
        val draining = launch { first.drain() }
        advanceTimeBy(atMillis)
        val rows = persistence.rows.toMap()
        val batches = persistence.batches.toMap()
        val archived = persistence.archive.toMap()
        val orphans = gallery.pendingFiles.associateWith { it.readBytes() }
        draining.cancel()
        draining.join()
        persistence.rows.clear(); persistence.rows.putAll(rows)
        persistence.batches.clear(); persistence.batches.putAll(batches)
        persistence.archive.clear(); persistence.archive.putAll(archived)
        orphans.forEach { (file, bytes) -> file.writeBytes(bytes) }
    }

    @Test
    fun killedDuringTheThirdOfFourWithTheCameraStillAttachedContinuesToFourOfFour() = scope.runTest {
        killedAt(14_000, fourFiles())
        assertEquals(
            listOf(ExportStatus.COMPLETED, ExportStatus.COMPLETED, ExportStatus.RUNNING, ExportStatus.QUEUED),
            persistence.rows.values.map { it.status },
        )
        assertEquals(1, gallery.pendingFiles.size)

        val restarted = engine()
        assertTrue(restarted.start())
        // Same batch, the two finished files untouched, the interrupted one back in line.
        assertEquals(1, restarted.queue.batches.value.size)
        assertEquals(
            listOf(ExportStatus.COMPLETED, ExportStatus.COMPLETED, ExportStatus.QUEUED, ExportStatus.QUEUED),
            restarted.queue.jobs.value.map { it.status },
        )
        assertTrue(gallery.pendingFiles.isEmpty())
        restarted.drain()
        assertEquals(List(4) { ExportStatus.COMPLETED }, jobs(restarted).map { it.status })
        assertEquals(Triple(4, 4, 0), batch(restarted).let { Triple(it.totalCount, it.succeededCount, it.failedCount) })
        assertEquals(BatchStatus.DONE, batch(restarted).status)
        assertEquals(
            setOf("a.jpg", "DJI_20261107113403_0005_D_slim.mp4", "DJI_20261107114219_0006_D_slim.mp4", "b.jpg"),
            gallery.published.keys,
        )
        // The first two were not converted or copied a second time.
        assertEquals(3, transcoder.calls.size)
    }

    @Test
    fun killedAndTheCameraIsGoneOnRestartEndsTheExportAsTwoOfFour() = scope.runTest {
        killedAt(14_000, fourFiles())
        source.plugged = false

        val restarted = engine()
        // Decided at startup, without starting any work.
        assertFalse(restarted.start())
        assertEquals(
            listOf(ExportStatus.COMPLETED, ExportStatus.COMPLETED, ExportStatus.FAILED, ExportStatus.FAILED),
            jobs(restarted).map { it.status },
        )
        assertEquals(listOf(usbRemoved, usbRemoved), jobs(restarted).drop(2).map { it.failure })
        val summary = batch(restarted)
        assertEquals(BatchStatus.DONE, summary.status)
        assertEquals(Triple(4, 2, 2), Triple(summary.totalCount, summary.succeededCount, summary.failedCount))
        assertTrue(restarted.queue.jobs.value.isEmpty())
        assertTrue(persistence.rows.isEmpty())
        assertTrue(gallery.pendingFiles.isEmpty())
        assertEquals(setOf("a.jpg", "DJI_20261107113403_0005_D_slim.mp4"), gallery.published.keys)
        restarted.drain()
        assertEquals(2, transcoder.calls.size)
    }

    @Test
    fun cameraUnpluggedAfterTheRestartIsHandledLikeAnyOtherUnplug() = scope.runTest {
        killedAt(14_000, fourFiles())
        val restarted = engine()
        assertTrue(restarted.start())
        val draining = launch { restarted.drain() }
        advanceTimeBy(4_000)
        source.plugged = false
        restarted.sourceDisconnected()
        draining.join()
        assertEquals(Triple(4, 2, 2), batch(restarted).let { Triple(it.totalCount, it.succeededCount, it.failedCount) })
        assertEquals(listOf(usbRemoved, usbRemoved), jobs(restarted).drop(2).map { it.failure })
        assertTrue(gallery.pendingFiles.isEmpty())
    }

    @Test
    fun killedTwiceStillContinues() = scope.runTest {
        killedAt(14_000, fourFiles())
        val second = engine()
        assertTrue(second.start())
        val draining = launch { second.drain() }
        advanceTimeBy(5_000)
        // killed again, same way
        val rows = persistence.rows.toMap()
        draining.cancel()
        draining.join()
        persistence.rows.clear(); persistence.rows.putAll(rows)

        val third = engine()
        assertTrue(third.start())
        third.drain()
        assertEquals(Triple(4, 4, 0), batch(third).let { Triple(it.totalCount, it.succeededCount, it.failedCount) })
        assertTrue(gallery.pendingFiles.isEmpty())
    }

    @Test
    fun killedWhileOnlyWaitingFilesRemainedDoesNotMistakeItForAnUnplug() = scope.runTest {
        // Killed in the gap between two files: nothing was running, so there is no
        // evidence about the camera either way and the queue is simply continued.
        val first = engine()
        first.enqueue(listOf(photo("a.jpg"), photo("b.jpg")))
        val restarted = engine()
        assertTrue(restarted.start())
        restarted.drain()
        assertEquals(Triple(2, 2, 0), batch(restarted).let { Triple(it.totalCount, it.succeededCount, it.failedCount) })
    }

    @Test
    fun startWithNothingToDoReportsSo() {
        assertFalse(engine().start())
    }

    @Test
    fun notEnoughSpaceFailsWithAClearMessageAndExportingAgainRetries() = scope.runTest {
        val engine = engine()
        gallery.free = 1024
        val request = photo("a.jpg")
        engine.enqueue(listOf(request))
        engine.drain()
        val failed = jobs(engine).single()
        assertEquals(ExportStatus.FAILED, failed.status)
        assertEquals(FailureReason.NO_SPACE, failed.failure)
        assertEquals(Triple(BatchStatus.DONE, 1, 1), batch(engine).let { Triple(it.status, it.totalCount, it.failedCount) })
        gallery.free = Long.MAX_VALUE
        assertTrue(engine.enqueue(listOf(request)))
        engine.drain()
        assertEquals(ExportStatus.COMPLETED, jobs(engine).last().status)
        assertNull(jobs(engine).last().failure)
    }

    @Test
    fun aKilledProcessBetweenLastFileAndSummaryStillGetsItsSummary() = scope.runTest {
        val first = engine()
        first.enqueue(listOf(photo("a.jpg")))
        val job = first.queue.jobs.value.single()
        first.queue.markRunning(job.id)
        first.queue.complete(job.id, ExportOutcome.COPIED, "gallery://a.jpg", 5000, null)
        // dies before the batch is finalized
        val second = engine()
        assertFalse(second.start())
        assertEquals(BatchStatus.DONE, batch(second).status)
        assertTrue(second.queue.jobs.value.isEmpty())
    }

    @Test
    fun readErrorsWithTheCameraStillAttachedFailOnlyThatFile() = scope.runTest {
        val engine = engine()
        engine.enqueue(listOf(photo("a.jpg")))
        source.failReads = true
        engine.drain()
        val failed = jobs(engine).single()
        assertEquals(ExportStatus.FAILED, failed.status)
        // Not one of the named reasons: the system's own message is kept for display.
        assertEquals(FailureReason.OTHER, failed.failure)
        assertEquals("read error", failed.errorMessage)
        assertTrue(gallery.pendingFiles.isEmpty())
    }

    @Test
    fun truncatedCopyIsNotPublished() = scope.runTest {
        val engine = engine()
        val request = photo("a.jpg")
        engine.enqueue(listOf(request.copy(sizeBytes = 9999)))
        engine.drain()
        assertEquals(ExportStatus.FAILED, jobs(engine).single().status)
        assertEquals(FailureReason.INCOMPLETE_COPY, jobs(engine).single().failure)
        assertTrue(gallery.published.isEmpty())
        assertTrue(gallery.pendingFiles.isEmpty())
    }

    @Test
    fun concurrentDrainsDoNotProcessAJobTwice() = scope.runTest {
        val engine = engine()
        engine.enqueue(listOf(video("DJI_20261107113403_0005_D.MP4")))
        val a = launch { engine.drain() }
        val b = launch { engine.drain() }
        advanceUntilIdle()
        a.join(); b.join()
        assertEquals(1, transcoder.calls.size)
    }

    @Test
    fun longCodecErrorsAreShortenedForTheList() {
        assertEquals("boom", ExportEngine.shortReason(IllegalStateException("boom\nsecond line")))
        assertEquals("IllegalStateException", ExportEngine.shortReason(IllegalStateException()))
        assertEquals(61, ExportEngine.shortReason(IllegalStateException("x".repeat(500))).length)
    }

    @Test
    fun outputNames() {
        assertEquals("DJI_1_D_slim.mp4", ExportEngine.slimName("DJI_1_D.MP4"))
        assertEquals(listOf("a_slim.mp4", "a.mov"), ExportEngine.galleryNames("a.mov", MediaKind.VIDEO))
        assertEquals(listOf("a.jpg"), ExportEngine.galleryNames("a.jpg", MediaKind.PHOTO))
    }
}
