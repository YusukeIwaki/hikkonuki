package app.hikkonuki.android.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
                job(5, "e.mp4", ExportStatus.WAITING_FOR_SOURCE),
            ),
            ExportBatch(1, BatchStatus.ACTIVE),
        )
        compose.onNodeWithText("エクスポート状況").assertIsDisplayed()
        compose.onNodeWithText("完了").assertIsDisplayed()
        compose.onNodeWithText("83.3 MB ・ DCIM/Hikkonuki").assertIsDisplayed()
        compose.onNodeWithText("エクスポート中 37%").assertIsDisplayed()
        compose.onNodeWithTag("exportProgress_2").assertIsDisplayed()
        compose.onNodeWithText("失敗").assertIsDisplayed()
        compose.onNodeWithText("スマートフォンの空き容量が足りません").assertIsDisplayed()
        compose.onNodeWithText("USBが取り外されました。接続し直すと再開します。").assertIsDisplayed()
        compose.onAllNodesWithText("待機中").assertCountEquals(2)
        // Finished rows cannot be cancelled; waiting, running and parked ones can.
        compose.onAllNodesWithTag("cancelJob_1").assertCountEquals(0)
        compose.onAllNodesWithTag("cancelJob_4").assertCountEquals(0)
        compose.onNodeWithTag("cancelJob_3").performClick()
        compose.onNodeWithTag("cancelJob_5").performClick()
        compose.onNodeWithTag("clearExportHistory").performClick()
        assertEquals(listOf("cancel 3", "cancel 5", "clear"), events)
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

    private fun app(library: FakeLibrary, exports: FakeExports, signal: () -> Int = { 0 }): AppViewModel {
        val viewModel = AppViewModel(library, exports)
        compose.setContent { HikkonukiTheme { HikkonukiApp(viewModel, openExportsSignal = signal()) } }
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
        app(library, exports) { signal.intValue }
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
