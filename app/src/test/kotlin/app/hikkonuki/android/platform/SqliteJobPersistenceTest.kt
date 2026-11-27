package app.hikkonuki.android.platform

import androidx.test.core.app.ApplicationProvider
import app.hikkonuki.android.core.BatchStatus
import app.hikkonuki.android.core.ExportOutcome
import app.hikkonuki.android.core.ExportQueue
import app.hikkonuki.android.core.ExportRequest
import app.hikkonuki.android.core.ExportStatus
import app.hikkonuki.android.core.FailureReason
import app.hikkonuki.android.core.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SqliteJobPersistenceTest {
    private fun persistence() = SqliteJobPersistence(ApplicationProvider.getApplicationContext(), "test.db")

    @Test
    fun everyFieldSurvivesAReopen() {
        val queue = ExportQueue(persistence()) { 1234L }
        queue.enqueue(
            listOf(
                ExportRequest("content://usb/a", "DJI_20261107113403_0005_D.MP4", MediaKind.VIDEO, 219_904_052),
                ExportRequest("/path/b.jpg", "b.jpg", MediaKind.PHOTO, 5000),
                ExportRequest("content://usb/c", "c.mp4", MediaKind.VIDEO, 1),
            ),
        )
        val (a, b, c) = queue.jobs.value
        queue.markRunning(a.id)
        queue.complete(a.id, ExportOutcome.CONVERTED, "content://media/1", 87_296_455, "2560×1440 ・ HEVC 10bit ・ 14Mbps")
        queue.markRunning(b.id)
        queue.fail(b.id, FailureReason.NO_SPACE, "needs 5 bytes, 1 available")

        val reopened = ExportQueue(persistence()) { 1234L }
        assertEquals(queue.jobs.value, reopened.jobs.value)
        assertEquals(queue.batches.value, reopened.batches.value)
        assertEquals(ExportStatus.QUEUED, reopened.jobs.value[2].status)
        assertEquals(reopened.batches.value.single().id, reopened.jobs.value[0].batchId)

        // Finishing collapses the batch to its summary, which also survives.
        reopened.remove(c.id)
        assertTrue(reopened.finalizeActiveBatch())
        val again = ExportQueue(persistence()) { 1234L }
        assertTrue(again.jobs.value.isEmpty())
        val summary = again.batches.value.single()
        assertEquals(BatchStatus.DONE, summary.status)
        assertEquals(Triple(2, 1, 1), Triple(summary.totalCount, summary.succeededCount, summary.failedCount))
        again.clearHistory()
        assertTrue(ExportQueue(persistence()).batches.value.isEmpty())
    }

    @Test
    fun deleteAndInterruptedRecovery() {
        val queue = ExportQueue(persistence())
        queue.enqueue(listOf(ExportRequest("a", "a.mp4", MediaKind.VIDEO, 1), ExportRequest("b", "b.mp4", MediaKind.VIDEO, 2)))
        queue.markRunning(queue.jobs.value[0].id)
        queue.setProgress(queue.jobs.value[0].id, 70)
        queue.remove(queue.jobs.value[1].id)

        val reopened = ExportQueue(persistence())
        assertEquals(listOf("a.mp4"), reopened.jobs.value.map { it.displayName })
        assertEquals(ExportStatus.RUNNING, reopened.jobs.value.single().status)
        assertEquals(0, reopened.jobs.value.single().progressPercent)
        reopened.recover()
        assertEquals(ExportStatus.QUEUED, ExportQueue(persistence()).jobs.value.single().status)
        reopened.remove(reopened.jobs.value.single().id)
        assertTrue(ExportQueue(persistence()).jobs.value.isEmpty())
    }
}
