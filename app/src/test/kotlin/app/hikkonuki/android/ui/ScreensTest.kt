package app.hikkonuki.android.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.hikkonuki.android.HikkonukiApp
import app.hikkonuki.android.core.BatchStatus
import app.hikkonuki.android.core.ExportBatch
import app.hikkonuki.android.core.ExportEngine
import app.hikkonuki.android.core.ExportJob
import app.hikkonuki.android.core.ExportOutcome
import app.hikkonuki.android.core.ExportStatus
import app.hikkonuki.android.core.MediaKind
import app.hikkonuki.android.theme.HikkonukiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val video = mediaItem("DJI_20261107113403_0005_D.MP4", size = 219_904_052).copy(durationMillis = 41_608)
    private val photo = mediaItem("DJI_20261107113403_0001_D.JPG")
    private val events = ArrayList<String>()

    private fun browser(state: BrowserUiState) = compose.setContent {
        HikkonukiTheme {
            BrowserScreen(
                state = state,
                onRefresh = { events += "refresh" }, onToggleLayout = { events += "layout" },
                onItemClick = { events += "click ${it.displayName}" }, onItemLongClick = { events += "long ${it.displayName}" },
                onToggleSelection = { events += "toggle ${it.displayName}" }, onClearSelection = { events += "clear" },
                onSelectAll = { events += "all" },
                onExportSelected = { events += "export" }, onOpenExports = { events += "exports" }, onPickFolder = { events += "pick" },
            )
        }
    }

    private fun loaded(vararg items: MediaItem) = BrowserUiState(usbConnected = true, loading = false, items = items.toList())

    @Test
    fun waitingForACamera() {
        browser(BrowserUiState(usbConnected = false, loading = false))
        compose.onNodeWithText("USBでカメラをさしてください").assertIsDisplayed()
        compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
    }

    @Test
    fun titleIsTheCameraNameWhileOneIsAttached() {
        val holder = liveBrowser(loaded(video, photo).copy(deviceName = "DJI OsmoPocket4P"))
        compose.onNodeWithText("DJI OsmoPocket4P").assertIsDisplayed()
        compose.onAllNodesWithText("Hikkonuki").assertCountEquals(0)
        // A camera that does not name itself, and no camera at all, show the app name.
        holder.value = loaded(video, photo)
        compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
        holder.value = BrowserUiState(usbConnected = false, loading = false)
        compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
        // The selection bar keeps its own title.
        holder.value = loaded(video, photo).copy(deviceName = "DJI OsmoPocket4P", selectedIds = setOf(video.id))
        compose.onNodeWithText("1件を選択中").assertIsDisplayed()
        compose.onAllNodesWithText("DJI OsmoPocket4P").assertCountEquals(0)
    }

    @Test
    fun firstConnectionAsksForTheCameraFolder() {
        browser(BrowserUiState(usbConnected = true, loading = false, folderPermissionError = true))
        compose.onNodeWithText("写真や動画が見つかりませんでした").assertIsDisplayed()
        compose.onNodeWithTag("folderPermissionError").assertIsDisplayed()
        compose.onNodeWithText("USBフォルダを開く").assertIsDisplayed()
        compose.onNodeWithTag("pickFolderButton").performClick()
        assertEquals(listOf("pick"), events)
    }

    @Test
    fun knownFolderWithNothingInItOffersAnotherOne() {
        browser(BrowserUiState(usbConnected = true, loading = false, watchedFolders = listOf("content://tree/1")))
        compose.onNodeWithText("前回のフォルダに写真や動画が見つかりませんでした").assertIsDisplayed()
        compose.onNodeWithText("別のUSBフォルダを選ぶ").assertIsDisplayed()
    }

    @Test
    fun actionBarHasLayoutRefreshAndMenu() {
        browser(loaded(video, photo))
        compose.onNodeWithTag("layoutToggle").performClick()
        compose.onNodeWithContentDescription("再読み込み").performClick()
        compose.onNodeWithContentDescription("メニュー").performClick()
        // The folder picker is only offered when there is nothing to show.
        compose.onAllNodesWithText("USBフォルダを開く").assertCountEquals(0)
        compose.onNodeWithText("エクスポート状況").performClick()
        assertEquals(listOf("layout", "refresh", "exports"), events)
    }

    @Test
    fun gridTapOpensAndLongPressSelects() {
        browser(loaded(video, photo))
        compose.onNodeWithText("0:41").assertIsDisplayed()
        compose.onNodeWithTag("mediaItem_${video.displayName}").performClick()
        compose.onNodeWithTag("mediaItem_${photo.displayName}").performTouchInput { longClick() }
        assertEquals(listOf("click ${video.displayName}", "long ${photo.displayName}"), events)
        compose.onAllNodesWithTag("exportSelectedFab").assertCountEquals(0)
    }

    @Test
    fun selectionModeTurnsTapsIntoTogglesAndOffersExport() {
        browser(loaded(video, photo).copy(selectedIds = setOf(video.id)))
        compose.onNodeWithText("1件を選択中").assertIsDisplayed()
        compose.onNodeWithTag("mediaItem_${photo.displayName}").performClick()
        compose.onNodeWithTag("selectAll").performClick()
        compose.onNodeWithText("エクスポート (1)", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("exportSelectedFab").performClick()
        compose.onNodeWithTag("clearSelection").performClick()
        assertEquals(listOf("toggle ${photo.displayName}", "all", "export", "clear"), events)
    }

    private val many = (1..90).map { mediaItem(String.format("DJI_202611071134%02d_%04d_D.JPG", it % 60, it)) }

    @Test
    fun appBarSlidesAwayWhileScrollingDownAndReturnsOnScrollingUp() {
        browser(loaded(*many.toTypedArray()))
        compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
        compose.onNodeWithTag("mediaGrid").performTouchInput { swipeUp() }
        compose.onNodeWithText("Hikkonuki").assertIsNotDisplayed()
        // Any scroll back up brings it back, without returning to the top of the list.
        compose.onNodeWithTag("mediaGrid").performTouchInput { swipeDown(startY = centerY, endY = centerY + 300f) }
        compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
        compose.onAllNodesWithTag("mediaItem_${many.first().displayName}").assertCountEquals(0)
    }

    @Test
    fun selectionBarStaysPutWhileScrolling() {
        browser(loaded(*many.toTypedArray()).copy(selectedIds = setOf(many.first().id)))
        compose.onNodeWithTag("mediaGrid").performTouchInput { swipeUp() }
        compose.onNodeWithText("1件を選択中").assertIsDisplayed()
        compose.onNodeWithTag("selectAll").assertIsDisplayed()
        compose.onNodeWithTag("exportSelectedFab").assertIsDisplayed()
    }

    @Test
    fun lastRowCanBeScrolledClearOfTheExportButton() {
        browser(loaded(*many.toTypedArray()).copy(selectedIds = setOf(many.first().id)))
        compose.onNodeWithTag("mediaGrid").performScrollToIndex(many.lastIndex)
        val last = compose.onNodeWithTag("mediaItem_${many.last().displayName}").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("exportSelectedFab").fetchSemanticsNode().boundsInRoot
        assertTrue("last row bottom ${last.bottom} vs button top ${button.top}", last.bottom <= button.top)
    }

    /** A browser whose state can be swapped while it is on screen. */
    private fun liveBrowser(initial: BrowserUiState): androidx.compose.runtime.MutableState<BrowserUiState> {
        val holder = androidx.compose.runtime.mutableStateOf(initial)
        compose.setContent {
            HikkonukiTheme {
                BrowserScreen(
                    state = holder.value, onRefresh = {}, onToggleLayout = {}, onItemClick = {}, onItemLongClick = {},
                    onToggleSelection = {}, onClearSelection = {}, onSelectAll = {}, onExportSelected = {}, onOpenExports = {}, onPickFolder = {},
                )
            }
        }
        return holder
    }

    private fun items(count: Int) = many.take(count)
    private fun tagOf(layout: BrowserLayout) = if (layout == BrowserLayout.GRID) "mediaGrid" else "mediaList"

    @Test
    fun listLayoutAlsoHidesTheBarOnScroll() {
        browser(loaded(*many.toTypedArray()).copy(layout = BrowserLayout.LIST))
        compose.onNodeWithTag("mediaList").performTouchInput { swipeUp() }
        compose.onNodeWithText("Hikkonuki").assertIsNotDisplayed()
        compose.onNodeWithTag("mediaList").performTouchInput { swipeDown(startY = centerY, endY = centerY + 300f) }
        compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
    }

    @Test
    fun barMovesExactlyAsFarAsTheContentDoes() {
        // No head start from the touch slop and no lag: after any drag the bar has moved
        // by the same number of pixels as the first row (until it is fully hidden).
        val holder = liveBrowser(BrowserUiState(usbConnected = false, loading = false))
        for (layout in BrowserLayout.entries) {
            holder.value = loaded(*many.toTypedArray()).copy(layout = layout)
            val first = "mediaItem_${many.first().displayName}"
            fun rowTop() = compose.onNodeWithTag(first).fetchSemanticsNode().boundsInRoot.top
            // The button inside the bar, not just the bar's edge: its contents move with it.
            fun barBottom() = compose.onNodeWithTag("layoutToggle").fetchSemanticsNode().positionInRoot.y
            val rowStart = rowTop()
            val barStart = barBottom()
            for (drag in listOf(5f, 40f, 70f, 55f)) {
                compose.onNodeWithTag(tagOf(layout)).performTouchInput { swipeUp(startY = centerY, endY = centerY - drag, durationMillis = 400) }
                assertEquals("after dragging $drag in $layout", rowStart - rowTop(), barStart - barBottom(), 0.5f)
            }
            assertTrue("the list did move", rowStart - rowTop() > 10f)
            holder.value = BrowserUiState(usbConnected = false, loading = false)
            compose.waitForIdle()
        }
    }

    @Test
    fun aListThatFitsOnScreenNeverLosesItsBar() {
        val holder = liveBrowser(BrowserUiState(usbConnected = false, loading = false))
        for (layout in BrowserLayout.entries) {
            for (count in listOf(1, 2, 3, 4, 6)) {
                holder.value = loaded(*items(count).toTypedArray()).copy(layout = layout)
                compose.onNodeWithTag(tagOf(layout)).performTouchInput { swipeUp() }
                compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
                compose.onNodeWithTag(tagOf(layout)).performTouchInput { swipeDown() }
                compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
            }
            holder.value = BrowserUiState(usbConnected = false, loading = false)
        }
    }

    @Test
    fun atAnyItemCountTheBarIsBackOnceTheListIsAtTheTop() {
        // Includes the awkward counts where the content is only a little taller than the
        // screen, so the list can scroll by less than the height of the bar.
        val holder = liveBrowser(BrowserUiState(usbConnected = false, loading = false))
        for (layout in BrowserLayout.entries) {
            for (count in (1..45) + listOf(60, 90)) {
                holder.value = loaded(*items(count).toTypedArray()).copy(layout = layout)
                val list = compose.onNodeWithTag(tagOf(layout))
                list.performTouchInput { swipeUp() }
                // Two long drags down are enough to reach the top from one drag up.
                repeat(3) { list.performTouchInput { swipeDown() } }
                compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
                compose.onNodeWithTag("mediaItem_${many.first().displayName}").assertIsDisplayed()
                // And a short nudge up and back never strands it either.
                list.performTouchInput { swipeUp(startY = centerY, endY = centerY - 40f) }
                list.performTouchInput { swipeDown(startY = centerY, endY = centerY + 40f) }
                compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
            }
        }
    }

    @Test
    fun barComesBackWhenTheListShrinksUnderIt() {
        val holder = liveBrowser(BrowserUiState(usbConnected = false, loading = false))
        for (layout in BrowserLayout.entries) {
            holder.value = loaded(*many.toTypedArray()).copy(layout = layout)
            compose.onNodeWithTag(tagOf(layout)).performTouchInput { swipeUp() }
            compose.onNodeWithText("Hikkonuki").assertIsNotDisplayed()
            // A rescan finds only three files left: no gesture could reveal the bar now.
            holder.value = loaded(*items(3).toTypedArray()).copy(layout = layout)
            compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
            // Unplugged, then plugged back in with the full card.
            holder.value = BrowserUiState(usbConnected = false, loading = false)
            compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
            holder.value = loaded(*many.toTypedArray()).copy(layout = layout)
            compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
        }
    }

    @Test
    fun barIsShownAfterSwitchingLayoutOrLeavingSelectionWhileScrolled() {
        val holder = liveBrowser(loaded(*many.toTypedArray()))
        compose.onNodeWithTag("mediaGrid").performTouchInput { swipeUp() }
        compose.onNodeWithText("Hikkonuki").assertIsNotDisplayed()
        // The other layout starts at its top.
        holder.value = holder.value.copy(layout = BrowserLayout.LIST)
        compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
        compose.onNodeWithTag("mediaList").performTouchInput { swipeUp() }
        compose.onNodeWithText("Hikkonuki").assertIsNotDisplayed()
        holder.value = holder.value.copy(layout = BrowserLayout.GRID)
        // The grid is still where it was left, mid-list: hidden, and one scroll up away.
        compose.onNodeWithTag("mediaGrid").performTouchInput { swipeDown(startY = centerY, endY = centerY + 300f) }
        compose.onNodeWithText("Hikkonuki").assertIsDisplayed()

        // Selection mode pins its own bar; leaving it mid-list must not strand the normal one.
        compose.onNodeWithTag("mediaGrid").performTouchInput { swipeUp() }
        holder.value = holder.value.copy(selectedIds = setOf(many[40].id))
        compose.onNodeWithText("1件を選択中").assertIsDisplayed()
        compose.onNodeWithTag("mediaGrid").performTouchInput { swipeUp() }
        compose.onNodeWithText("1件を選択中").assertIsDisplayed()
        holder.value = holder.value.copy(selectedIds = emptySet())
        compose.onNodeWithTag("mediaGrid").performTouchInput { swipeDown(startY = centerY, endY = centerY + 300f) }
        compose.onNodeWithText("Hikkonuki").assertIsDisplayed()
    }

    @Test
    fun listLayoutShowsSizeAndDate() {
        browser(loaded(video).copy(layout = BrowserLayout.LIST))
        compose.onNodeWithTag("mediaList").assertIsDisplayed()
        compose.onNodeWithText("209.7 MB ・ ${formatDate(video.dateTakenMillis)}").assertIsDisplayed()
    }

    private fun job(id: Long, name: String, status: ExportStatus) =
        ExportJob(id = id, batchId = 1, sourceId = "s$id", displayName = name, kind = MediaKind.VIDEO, sourceSizeBytes = 219_904_052, status = status)

    private fun exportsScreen(jobs: List<ExportJob>, batch: ExportBatch?) = compose.setContent {
        HikkonukiTheme {
            ExportsScreen(
                jobs = jobs, batch = batch, onBack = { events += "back" },
                onCancel = { events += "cancel $it" }, onClearHistory = { events += "clear" },
            )
        }
    }

    @Test
    fun exportsScreenListsEachFileWhileTheBatchIsActive() {
        exportsScreen(
            listOf(
                job(1, "a.mp4", ExportStatus.COMPLETED).copy(outcome = ExportOutcome.CONVERTED, outputSizeBytes = 87_296_455),
                job(2, "b.mp4", ExportStatus.RUNNING).copy(progressPercent = 37),
                job(3, "c.mp4", ExportStatus.QUEUED),
                job(4, "d.mp4", ExportStatus.FAILED).copy(errorMessage = "スマートフォンの空き容量が足りません"),
                job(5, "e.mp4", ExportStatus.FAILED).copy(errorMessage = ExportEngine.USB_REMOVED_MESSAGE),
            ),
            ExportBatch(1, BatchStatus.ACTIVE),
        )
        compose.onNodeWithText("エクスポート状況").assertIsDisplayed()
        compose.onNodeWithText("完了").assertIsDisplayed()
        compose.onNodeWithText("83.3 MB ・ DCIM/Hikkonuki").assertIsDisplayed()
        compose.onNodeWithText("エクスポート中 37%").assertIsDisplayed()
        compose.onNodeWithTag("exportProgress_2").assertIsDisplayed()
        compose.onAllNodesWithText("失敗").assertCountEquals(2)
        compose.onNodeWithText("スマートフォンの空き容量が足りません").assertIsDisplayed()
        compose.onNodeWithText("USBが取り外されました。接続し直して再度エクスポートしてください。").assertIsDisplayed()
        compose.onNodeWithText("待機中").assertIsDisplayed()
        // Finished rows cannot be cancelled; waiting and running ones can.
        compose.onAllNodesWithTag("cancelJob_1").assertCountEquals(0)
        compose.onAllNodesWithTag("cancelJob_4").assertCountEquals(0)
        compose.onAllNodesWithTag("cancelJob_5").assertCountEquals(0)
        compose.onNodeWithTag("cancelJob_3").performClick()
        compose.onNodeWithTag("clearExportHistory").performClick()
        assertEquals(listOf("cancel 3", "clear"), events)
    }

    @Test
    fun exportsScreenShowsOnlyTheResultOnceTheBatchIsDone() {
        exportsScreen(emptyList(), ExportBatch(1, BatchStatus.DONE, totalCount = 5, succeededCount = 4, failedCount = 1, updatedAtMillis = 1_780_000_000_000))
        compose.onNodeWithTag("exportsSummary").assertIsDisplayed()
        compose.onNodeWithText("エクスポート結果").assertIsDisplayed()
        compose.onNodeWithText("5件中 4件成功、1件失敗").assertIsDisplayed()
        compose.onNodeWithText("完了日時: ${formatDetailDate(1_780_000_000_000)}").assertIsDisplayed()
        compose.onAllNodesWithTag("exportsList").assertCountEquals(0)
        compose.onNodeWithTag("clearExportHistory").assertIsDisplayed()
    }

    @Test
    fun exportsScreenWithoutFailuresDoesNotMentionThem() {
        exportsScreen(emptyList(), ExportBatch(1, BatchStatus.DONE, totalCount = 3, succeededCount = 3))
        compose.onNodeWithText("3件中 3件成功").assertIsDisplayed()
    }

    @Test
    fun exportsScreenWithNoHistory() {
        exportsScreen(emptyList(), null)
        compose.onNodeWithText("エクスポート履歴がありません").assertIsDisplayed()
        compose.onAllNodesWithTag("clearExportHistory").assertCountEquals(0)
        compose.onNodeWithTag("exportsBack").performClick()
        assertEquals(listOf("back"), events)
    }

    private fun app(library: FakeLibrary, exports: FakeExports, signal: () -> Int = { 0 }, usbAttached: () -> Int = { 0 }): AppViewModel {
        val viewModel = AppViewModel(library, exports)
        compose.setContent { HikkonukiTheme { HikkonukiApp(viewModel, openExportsSignal = signal(), showBrowserSignal = usbAttached()) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("mediaGrid").fetchSemanticsNodes().isNotEmpty() }
        return viewModel
    }

    @Test
    fun wholeFlowFromPluggingInToTheExportResult() {
        val library = FakeLibrary().apply {
            snapshot = LibrarySnapshot(true, listOf(video, photo))
            details[video.id] = MediaDetails(3840, 2160, 41_608, null)
        }
        val exports = FakeExports()
        val viewModel = app(library, exports)

        // Look at one, come back.
        compose.onNodeWithTag("mediaItem_${video.displayName}").performClick()
        compose.onNodeWithTag("metadataPanel").assertIsDisplayed()
        compose.onNodeWithText("3840 × 2160").assertIsDisplayed()
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithTag("detailBack").performClick()

        // Select both and export.
        compose.onNodeWithTag("mediaItem_${video.displayName}").performTouchInput { longClick() }
        compose.onNodeWithTag("mediaItem_${photo.displayName}").performClick()
        compose.onNodeWithTag("exportSelectedFab").performClick()
        assertEquals(listOf(video.toRequest(), photo.toRequest()), exports.exported.single())
        compose.onNodeWithTag("exportsList").assertIsDisplayed()
        compose.onNodeWithText(video.displayName).assertIsDisplayed()
        compose.onAllNodesWithText("待機中").assertCountEquals(2)

        // The engine finishes; the rows give way to the result without the screen being told.
        exports.finishAll(succeeded = 2, failed = 0, atMillis = 1_780_000_000_000)
        compose.onNodeWithText("エクスポート結果").assertIsDisplayed()
        compose.onNodeWithText("2件中 2件成功").assertIsDisplayed()
        compose.onAllNodesWithText(video.displayName).assertCountEquals(0)

        compose.onNodeWithTag("clearExportHistory").performClick()
        compose.onNodeWithText("エクスポート履歴がありません").assertIsDisplayed()
        compose.onNodeWithTag("exportsBack").performClick()
        compose.onNodeWithTag("mediaGrid").assertIsDisplayed()
        assertTrue(viewModel.state.value.selectedIds.isEmpty())
    }

    @Test
    fun exportingFromTheDetailMenuAndNotificationDeepLink() {
        val library = FakeLibrary().apply { snapshot = LibrarySnapshot(true, listOf(video, photo)) }
        val exports = FakeExports()
        val signal = androidx.compose.runtime.mutableIntStateOf(0)
        app(library, exports, signal = { signal.intValue })
        signal.intValue = 1
        compose.onNodeWithTag("exportsEmpty").assertIsDisplayed()
        compose.onNodeWithTag("exportsBack").performClick()
        compose.onNodeWithTag("mediaItem_${photo.displayName}").performClick()
        compose.onNodeWithTag("detailMenu").performClick()
        compose.onNodeWithText("エクスポート").performClick()
        assertEquals(listOf(photo.toRequest()), exports.exported.single())
        compose.onNodeWithTag("exportsList").assertIsDisplayed()
    }

    @Test
    fun pluggingACameraInAlwaysLandsOnTheMediaList() {
        val library = FakeLibrary().apply { snapshot = LibrarySnapshot(true, listOf(video, photo)) }
        val exports = FakeExports()
        val attached = androidx.compose.runtime.mutableIntStateOf(0)
        app(library, exports, usbAttached = { attached.intValue })

        // The app was left on the exports screen of an earlier session.
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("エクスポート状況").performClick()
        compose.onNodeWithTag("exportsEmpty").assertIsDisplayed()
        attached.intValue++
        compose.onNodeWithTag("mediaGrid").assertIsDisplayed()
        compose.onAllNodesWithTag("exportsEmpty").assertCountEquals(0)

        // Same from a detail screen reached through the exports screen's back stack.
        compose.onNodeWithTag("mediaItem_${photo.displayName}").performClick()
        compose.onNodeWithTag("metadataPanel").assertIsDisplayed()
        attached.intValue++
        compose.onNodeWithTag("mediaGrid").assertIsDisplayed()

        // Already on the list: nothing happens, and back still leaves the app as before.
        attached.intValue++
        compose.onNodeWithTag("mediaGrid").assertIsDisplayed()
    }

    @Test
    fun detailScreenSaysSoWhenTheCameraIsGone() {
        val library = FakeLibrary().apply {
            snapshot = LibrarySnapshot(true, listOf(video))
            present = false
        }
        app(library, FakeExports())
        compose.onNodeWithTag("mediaItem_${video.displayName}").performClick()
        compose.onNodeWithTag("mediaUnavailable").assertIsDisplayed()
        compose.onNodeWithText("戻って、USBを接続し直してください。").assertIsDisplayed()
    }
}
