package app.hikkonuki.android.ui

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val library = FakeLibrary()
    private val exports = FakeExports()
    private val video = mediaItem("DJI_20261107113403_0005_D.MP4")
    private val photo = mediaItem("DJI_20261107113403_0001_D.JPG")
    private val other = mediaItem("VID_20260510_132733_019.mp4")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun connected(vararg items: MediaItem, folders: List<String> = emptyList()) {
        library.snapshot = LibrarySnapshot(true, items.toList(), folders)
    }

    @Test
    fun showsNothingConnectedUntilACameraAppears() = runTest(dispatcher) {
        val viewModel = AppViewModel(library, exports)
        assertTrue(viewModel.state.value.loading)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.usbConnected)
        assertEquals(1, exports.resumed)

        connected(video, photo)
        viewModel.onUsbChanged()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.usbConnected)
        assertEquals(listOf(video, photo), viewModel.state.value.items)
    }

    @Test
    fun selectionModeEndsWhenTheLastItemIsDeselected() = runTest(dispatcher) {
        connected(video, photo, other)
        val viewModel = AppViewModel(library, exports)
        advanceUntilIdle()
        viewModel.startSelection(video)
        viewModel.toggleSelection(photo)
        assertEquals(setOf(video.id, photo.id), viewModel.state.value.selectedIds)
        viewModel.toggleSelection(video)
        viewModel.toggleSelection(photo)
        assertFalse(viewModel.state.value.selectionMode)
        viewModel.selectAll()
        assertEquals(3, viewModel.state.value.selectedIds.size)
        viewModel.clearSelection()
        assertFalse(viewModel.state.value.selectionMode)
    }

    @Test
    fun exportSelectedHandsOverTheFilesAndLeavesSelectionMode() = runTest(dispatcher) {
        connected(video, photo, other)
        val viewModel = AppViewModel(library, exports)
        advanceUntilIdle()
        viewModel.startSelection(photo)
        viewModel.toggleSelection(other)
        viewModel.exportSelected()
        assertEquals(listOf(photo.toRequest(), other.toRequest()), exports.exported.single())
        assertFalse(viewModel.state.value.selectionMode)
        viewModel.exportSelected()
        assertEquals(1, exports.exported.size)
        viewModel.exportItem(video)
        assertEquals(listOf(video.toRequest()), exports.exported.last())
    }

    @Test
    fun unpluggingDropsSelectionsOfVanishedFiles() = runTest(dispatcher) {
        connected(video, photo)
        val viewModel = AppViewModel(library, exports)
        advanceUntilIdle()
        viewModel.selectAll()
        connected(photo)
        viewModel.onUsbChanged()
        advanceUntilIdle()
        assertEquals(setOf(photo.id), viewModel.state.value.selectedIds)
        library.snapshot = LibrarySnapshot(false, emptyList())
        viewModel.onUsbChanged()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.selectionMode)
        assertFalse(viewModel.state.value.usbConnected)
    }

    @Test
    fun aRefreshRequestedDuringAScanIsNotLost() = runTest(dispatcher) {
        library.gate = CompletableDeferred()
        val viewModel = AppViewModel(library, exports)
        runCurrent()
        // The first scan has already read "nothing"; the camera mounts while it is in flight.
        connected(video)
        viewModel.onUsbChanged()
        library.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, library.loads)
        assertEquals(listOf(video), viewModel.state.value.items)
    }

    @Test
    fun keepsLookingWhileAKnownCameraIsStillMounting() = runTest(dispatcher) {
        connected(folders = listOf("content://tree/1"))
        val viewModel = AppViewModel(library, exports)
        runCurrent()
        assertTrue(viewModel.state.value.canPickFolder)
        advanceTimeBy(AppViewModel.MOUNT_RETRY_MILLIS * 2 + 1)
        connected(video, folders = listOf("content://tree/1"))
        advanceUntilIdle()
        assertEquals(listOf(video), viewModel.state.value.items)
        val loads = library.loads
        advanceTimeBy(60_000)
        assertEquals(loads, library.loads)
    }

    @Test
    fun givesUpRetryingAndNeverRetriesWithoutAKnownFolder() = runTest(dispatcher) {
        connected()
        val viewModel = AppViewModel(library, exports)
        advanceUntilIdle()
        assertEquals(1, library.loads)
        assertTrue(viewModel.state.value.canPickFolder)

        connected(folders = listOf("content://tree/1"))
        viewModel.onUsbChanged()
        advanceUntilIdle()
        assertEquals(2 + AppViewModel.MAX_MOUNT_RETRIES, library.loads)
    }

    @Test
    fun fillsInVideoDurationsAfterTheListIsShown() = runTest(dispatcher) {
        connected(video, photo, other)
        library.details[video.id] = MediaDetails(3840, 2160, 41_608, null)
        val viewModel = AppViewModel(library, exports)
        advanceUntilIdle()
        val shown = viewModel.state.value.items
        assertEquals(41_608L, shown[0].durationMillis)
        assertEquals(3840, shown[0].width)
        assertEquals(null, shown[2].durationMillis)
        // Photos are read on demand, and a rescan keeps what was already learned.
        library.details[photo.id] = MediaDetails(4000, 3000, null, 1_700_000_000_000)
        viewModel.ensureDetails(photo)
        advanceUntilIdle()
        assertEquals(1_700_000_000_000, viewModel.state.value.items[1].dateTakenMillis)
        viewModel.onUsbChanged()
        advanceUntilIdle()
        assertEquals(41_608L, viewModel.state.value.items[0].durationMillis)
        assertEquals(4000, viewModel.state.value.items[1].width)
    }

    @Test
    fun folderGrantThatCannotBeSavedIsReported() = runTest(dispatcher) {
        connected()
        val viewModel = AppViewModel(library, exports)
        advanceUntilIdle()
        library.rememberResult = false
        viewModel.onFolderPicked(Uri.parse("content://tree/1"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.folderPermissionError)
        assertEquals(1, library.loads)

        library.rememberResult = true
        connected(video)
        viewModel.onFolderPicked(Uri.parse("content://tree/1"))
        advanceUntilIdle()
        assertFalse(viewModel.state.value.folderPermissionError)
        assertEquals(listOf(video), viewModel.state.value.items)
    }

    @Test
    fun resumeOnlyRescansWhenSomethingChanged() = runTest(dispatcher) {
        connected(video)
        val viewModel = AppViewModel(library, exports)
        advanceUntilIdle()
        viewModel.refreshOnResume()
        advanceUntilIdle()
        assertEquals(1, library.loads)
        library.snapshot = LibrarySnapshot(false, emptyList())
        viewModel.refreshOnResume()
        advanceUntilIdle()
        assertEquals(2, library.loads)
        // Nothing on screen: always worth another look.
        viewModel.refreshOnResume()
        advanceUntilIdle()
        assertEquals(3, library.loads)
    }

    @Test
    fun exportCommandsAreForwarded() = runTest(dispatcher) {
        val viewModel = AppViewModel(library, exports)
        viewModel.cancelExport(7)
        viewModel.clearExportHistory()
        assertEquals(listOf(7L), exports.cancelled)
        assertEquals(1, exports.cleared)
        assertTrue(viewModel.exportJobs === exports.jobs && viewModel.exportBatches === exports.batches)
    }
}
