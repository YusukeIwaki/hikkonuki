package app.hikkonuki.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportQueueTest {
    private val persistence = MemoryPersistence()
    private var clock = 1000L
    private fun queue() = ExportQueue(persistence) { clock++ }
    private fun request(name: String, size: Long = 100, id: String = "usb://$name", kind: MediaKind = MediaKind.VIDEO) =
        ExportRequest(id, name, kind, size)

    @Test
    fun enqueueKeepsOrderAndPersists() {
        val queue = queue()
        assertEquals(2, queue.enqueue(listOf(request("a.mp4"), request("b.jpg", kind = MediaKind.PHOTO))))
        assertEquals(listOf("a.mp4", "b.jpg"), queue.jobs.value.map { it.displayName })
        assertEquals("a.mp4", queue.nextQueued()!!.displayName)
        assertEquals(queue.jobs.value, queue().jobs.value)
    }

    @Test
    fun sameFileIsNeverQueuedTwice() {
        val queue = queue()
        queue.enqueue(listOf(request("a.mp4"), request("a.mp4", id = "other://a.mp4"), request("A.MP4")))
        assertEquals(1, queue.jobs.value.size)
        assertEquals(0, queue.enqueue(listOf(request("a.mp4"))))
        // Same name, different size: a different recording.
        assertEquals(1, queue.enqueue(listOf(request("a.mp4", size = 200))))
        assertEquals(2, queue.jobs.value.size)
    }

    @Test
    fun completedFileIsSkippedWhileItsSessionIsStillActive() {
        val queue = queue()
        queue.enqueue(listOf(request("a.mp4"), request("b.mp4")))
        val a = queue.nextQueued()!!
        queue.markRunning(a.id)
        queue.complete(a.id, ExportOutcome.CONVERTED, "out://a", 10, null)
        assertEquals(0, queue.enqueue(listOf(request("a.mp4"))))
        assertEquals(listOf(ExportStatus.COMPLETED, ExportStatus.QUEUED), queue.jobs.value.map { it.status })
    }

    @Test
    fun finishedBatchKeepsOnlyItsSummaryAndTheNextExportStartsANewOne() {
        val queue = queue()
        queue.enqueue(listOf(request("a.mp4"), request("b.mp4")))
        assertFalse(queue.finalizeActiveBatch())
        queue.jobs.value.forEach { queue.markRunning(it.id) }
        queue.complete(queue.jobs.value[0].id, ExportOutcome.CONVERTED, "out://a", 10, null)
        queue.fail(queue.jobs.value[1].id, FailureReason.OTHER, "boom")
        assertTrue(queue.finalizeActiveBatch())
        assertTrue(queue.jobs.value.isEmpty())
        assertTrue(persistence.rows.isEmpty())
        val done = queue.batches.value.single()
        assertEquals(BatchStatus.DONE, done.status)
        assertEquals(Triple(2, 1, 1), Triple(done.totalCount, done.succeededCount, done.failedCount))
        assertFalse(queue.finalizeActiveBatch())

        // A file from the finished batch can be exported again: its rows are gone.
        assertEquals(2, queue.enqueue(listOf(request("a.mp4"), request("c.mp4"))))
        assertEquals(listOf(BatchStatus.ACTIVE, BatchStatus.DONE), queue.batches.value.map { it.status })
        assertEquals(listOf("a.mp4", "c.mp4"), queue.jobs.value.map { it.displayName })
        assertTrue(queue.jobs.value.all { it.batchId == queue.batches.value.first().id })
        assertEquals(queue.batches.value, queue().batches.value)
    }

    @Test
    fun atMostTwoBatchesAreKept() {
        val queue = queue()
        repeat(4) { round ->
            queue.enqueue(listOf(request("$round.mp4")))
            val job = queue.nextQueued()!!
            queue.markRunning(job.id)
            queue.complete(job.id, ExportOutcome.COPIED, "out://$round", 1, null)
            queue.finalizeActiveBatch()
        }
        assertEquals(2, queue.batches.value.size)
        assertEquals(2, persistence.batches.size)
        queue.enqueue(listOf(request("x.mp4")))
        assertEquals(listOf(BatchStatus.ACTIVE, BatchStatus.DONE), queue.batches.value.map { it.status })
    }

    @Test
    fun batchWithWorkLeftStaysOpenAndIsJoinedByLaterExports() {
        val queue = queue()
        queue.enqueue(listOf(request("a.mp4"), request("b.mp4")))
        val (a, _) = queue.jobs.value
        queue.markRunning(a.id)
        queue.complete(a.id, ExportOutcome.CONVERTED, "out://a", 10, null)
        assertFalse(queue.finalizeActiveBatch())
        queue.enqueue(listOf(request("c.mp4")))
        assertEquals(1, queue.batches.value.size)
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4"), queue.jobs.value.map { it.displayName })
    }

    @Test
    fun removingTheLastRowRemovesItsBatchAndClearHistoryRemovesEverything() {
        val queue = queue()
        queue.enqueue(listOf(request("a.mp4")))
        queue.remove(queue.jobs.value.single().id)
        assertTrue(queue.batches.value.isEmpty())
        assertTrue(persistence.batches.isEmpty())

        queue.enqueue(listOf(request("a.mp4")))
        val job = queue.nextQueued()!!
        queue.markRunning(job.id)
        queue.complete(job.id, ExportOutcome.COPIED, "out://a", 1, null)
        queue.finalizeActiveBatch()
        queue.enqueue(listOf(request("b.mp4")))
        queue.clearHistory()
        assertTrue(queue.batches.value.isEmpty() && queue.jobs.value.isEmpty())
        assertTrue(persistence.batches.isEmpty() && persistence.rows.isEmpty())
        assertEquals(0, queue.enqueue(emptyList()))
        assertTrue(queue.batches.value.isEmpty())
    }

    @Test
    fun failedFileCanBeEnqueuedAgain() {
        val queue = queue()
        queue.enqueue(listOf(request("a.mp4"), request("b.mp4")))
        val a = queue.nextQueued()!!
        queue.markRunning(a.id)
        queue.fail(a.id, FailureReason.OTHER, "boom")
        assertEquals(1, queue.enqueue(listOf(request("a.mp4"))))
        val again = queue.jobs.value.single { it.displayName == "a.mp4" }
        assertEquals(ExportStatus.QUEUED, again.status)
        assertNull(again.errorMessage)
        assertNull(again.failure)
    }

    @Test
    fun progressIsNotPersistedButStateIs() {
        val queue = queue()
        queue.enqueue(listOf(request("a.mp4")))
        val a = queue.nextQueued()!!
        queue.markRunning(a.id)
        queue.setProgress(a.id, 42)
        assertEquals(42, queue.jobs.value.single().progressPercent)
        assertEquals(0, persistence.rows.getValue(a.id).progressPercent)
        assertEquals(ExportStatus.RUNNING, persistence.rows.getValue(a.id).status)
        queue.setProgress(a.id, 250)
        assertEquals(100, queue.jobs.value.single().progressPercent)
    }

    @Test
    fun recoverPutsInterruptedWorkBackInLine() {
        val first = queue()
        first.enqueue(listOf(request("a.mp4"), request("b.mp4")))
        first.markRunning(first.nextQueued()!!.id)
        // process dies here
        val second = queue()
        assertEquals(ExportStatus.RUNNING, second.jobs.value[0].status)
        assertEquals(1, second.recover())
        assertEquals(listOf(ExportStatus.QUEUED, ExportStatus.QUEUED), second.jobs.value.map { it.status })
        assertEquals("a.mp4", second.nextQueued()!!.displayName)
    }

    @Test
    fun failQueuedLeavesFinishedAndRunningRowsAlone() {
        val queue = queue()
        queue.enqueue(listOf(request("a.mp4"), request("b.mp4"), request("c.mp4"), request("d.mp4")))
        val (a, b, c, d) = queue.jobs.value
        queue.markRunning(a.id); queue.complete(a.id, ExportOutcome.COPIED, "out://a", 1, null)
        queue.markRunning(b.id)
        assertEquals(2, queue.failQueued(FailureReason.USB_REMOVED))
        assertEquals(
            listOf(ExportStatus.COMPLETED, ExportStatus.RUNNING, ExportStatus.FAILED, ExportStatus.FAILED),
            queue.jobs.value.map { it.status },
        )
        assertEquals(List(2) { FailureReason.USB_REMOVED }, listOf(c, d).map { persistence.rows.getValue(it.id).failure })
        assertEquals(0, queue.failQueued(FailureReason.USB_REMOVED))
    }

    @Test
    fun updatesToRemovedJobsAreIgnored() {
        val queue = queue()
        queue.enqueue(listOf(request("a.mp4")))
        val a = queue.nextQueued()!!
        queue.remove(a.id)
        assertNull(queue.complete(a.id, ExportOutcome.CONVERTED, "out://a", 1, null))
        queue.setProgress(a.id, 5)
        assertTrue(queue.jobs.value.isEmpty())
        assertTrue(persistence.rows.isEmpty())
    }
}
