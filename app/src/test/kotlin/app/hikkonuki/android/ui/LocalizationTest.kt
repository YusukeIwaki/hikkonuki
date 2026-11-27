package app.hikkonuki.android.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.hikkonuki.android.R
import app.hikkonuki.android.core.BatchStatus
import app.hikkonuki.android.core.ExportBatch
import app.hikkonuki.android.core.ExportJob
import app.hikkonuki.android.core.ExportStatus
import app.hikkonuki.android.core.FailureReason
import app.hikkonuki.android.core.MediaKind
import app.hikkonuki.android.theme.HikkonukiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The app speaks English by default and Japanese, Chinese (Simplified and Traditional)
 * and Korean where the device, or the per-app language setting, asks for it.
 */
@RunWith(RobolectricTestRunner::class)
class LocalizationTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun localized(tag: String): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })

    @Test
    fun pluralsExistInEveryLanguage() {
        val english = localized("en").resources.getQuantityString(R.plurals.result_all_succeeded, 7, 7)
        for (tag in LANGUAGES - "en") {
            assertNotEquals(tag, english, localized(tag).resources.getQuantityString(R.plurals.result_all_succeeded, 7, 7))
        }
    }

    /** Every string this app defines (R classes are non-transitive, so no library strings). */
    private fun ownKeys(): List<Int> = R.string::class.java.fields.map { it.getInt(null) }

    @Test
    fun englishIsWhatAnUnsupportedLanguageGets() {
        assertEquals("Connect a camera via USB", localized("en").getString(R.string.connect_camera))
        assertEquals("Connect a camera via USB", localized("fr").getString(R.string.connect_camera))
        assertEquals("Connect a camera via USB", localized("de-CH").getString(R.string.connect_camera))
    }

    @Test
    fun eachLanguageIsPickedByTheLocalesPeopleActuallyHave() {
        fun text(tag: String) = localized(tag).getString(R.string.connect_camera)
        assertEquals("USBでカメラをさしてください", text("ja-JP"))
        assertEquals("USB로 카메라를 연결하세요", text("ko-KR"))
        // Simplified for mainland China and Singapore...
        assertEquals("请通过 USB 连接相机", text("zh-CN"))
        assertEquals("请通过 USB 连接相机", text("zh-Hans-SG"))
        // ...Traditional for Taiwan, Hong Kong and Macau.
        assertEquals("請透過 USB 連接相機", text("zh-TW"))
        assertEquals("請透過 USB 連接相機", text("zh-Hant-HK"))
        assertEquals("請透過 USB 連接相機", text("zh-MO"))
    }

    @Test
    fun theProductNameIsNeverTranslated() {
        for (tag in LANGUAGES) assertEquals(tag, "Hikkonuki", localized(tag).getString(R.string.app_name))
    }

    @Test
    fun everyStringIsTranslatedInEveryLanguage() {
        val english = localized("en")
        assertTrue("found only ${ownKeys().size} strings", ownKeys().size > 50)
        for (tag in LANGUAGES - "en") {
            val other = localized(tag)
            for (id in ownKeys()) {
                val name = context.resources.getResourceEntryName(id)
                if (name in SAME_IN_EVERY_LANGUAGE) continue
                assertNotEquals("$name is not translated for $tag", english.getString(id), other.getString(id))
            }
        }
    }

    @Test
    fun placeholdersMatchTheEnglishOnes() {
        // A translation that drops or mistypes %1$d would crash or print nonsense at runtime.
        val placeholder = Regex("""%(\d+\$)?[ds]|%%""")
        val english = localized("en")
        for (tag in LANGUAGES - "en") {
            val other = localized(tag)
            for (id in ownKeys()) {
                val name = context.resources.getResourceEntryName(id)
                fun args(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()
                assertEquals("$name in $tag", args(english.getString(id)), args(other.getString(id)))
            }
        }
    }

    @Test
    fun formattedStringsReadNaturally() {
        fun summary(tag: String) = localized(tag).getString(R.string.result_summary_with_failures, 5, 4, 1)
        assertEquals("4 of 5 files exported, 1 failed", summary("en"))
        assertEquals("5件中 4件成功、1件失敗", summary("ja"))
        assertEquals("共 5 个文件，4 个导出成功，1 个导出失败", summary("zh-CN"))
        assertEquals("共 5 個檔案，4 個匯出成功，1 個匯出失敗", summary("zh-TW"))
        assertEquals("파일 5개 중 4개 내보내기 성공, 1개 실패", summary("ko"))
        assertEquals("Exporting 37%", localized("en").getString(R.string.status_running, 37))
        assertEquals("Exporting clip.mp4 (37%)", localized("en").getString(R.string.notification_exporting, "clip.mp4", 37))
        assertEquals("Export of clip.mp4 canceled", localized("en").getString(R.string.notification_cancelled, "clip.mp4"))
        assertEquals("clip.mp4 をエクスポート中 37%", localized("ja").getString(R.string.notification_exporting, "clip.mp4", 37))
    }

    @Test
    fun everythingSucceededIsSaidPlainlyAndEnglishHasASingular() {
        fun all(tag: String, count: Int) = localized(tag).resources.getQuantityString(R.plurals.result_all_succeeded, count, count)
        assertEquals("All 12 files exported", all("en", 12))
        assertEquals("1 file exported", all("en", 1))
        assertEquals("12件中 12件成功", all("ja", 12))
        assertEquals("1件中 1件成功", all("ja", 1))
        assertEquals("已成功导出全部 12 个文件", all("zh-CN", 12))
        assertEquals("已成功匯出全部 12 個檔案", all("zh-TW", 12))
        assertEquals("파일 12개 모두 내보내기 완료", all("ko", 12))
    }

    @Test
    fun fileTypesAreNamedTheWayPeopleKnowThem() {
        assertEquals("JPEG", Formats.fileType("DJI_1_D.JPG", "image/jpeg"))
        assertEquals("MP4", Formats.fileType("VID_1.mp4", "video/mp4"))
        assertEquals("HEIC", Formats.fileType("a.heic", "image/heic"))
        assertEquals("DNG", Formats.fileType("a.DNG", "image/x-adobe-dng"))
        assertEquals("application/x-thing", Formats.fileType("a.thing", "application/x-thing"))
    }

    @Test
    fun datesAndSizesFollowTheLanguage() {
        val millis = 1_780_000_000_000 // 2026-05-28
        val formatted = LANGUAGES.associateWith { Formats.date(millis, Locale.forLanguageTag(it))!! }
        assertTrue(formatted.toString(), formatted.getValue("en").contains("May"))
        assertTrue(formatted.toString(), formatted.getValue("ja").startsWith("2026/05/2"))
        assertTrue(formatted.toString(), formatted.getValue("ko").startsWith("2026. 5. 2"))
        assertEquals("209.7 MB", Formats.size(219_904_052, Locale.US))
        assertEquals(null, Formats.size(0, Locale.US))
        assertEquals(null, Formats.date(null, Locale.US))
    }

    private val video = mediaItem("DJI_20261107113403_0005_D.MP4", size = 219_904_052)

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun screensInEnglish() {
        val state = androidx.compose.runtime.mutableStateOf(BrowserUiState(usbConnected = false, loading = false))
        val exports = androidx.compose.runtime.mutableStateOf<Pair<List<ExportJob>, ExportBatch?>?>(null)
        compose.setContent {
            HikkonukiTheme {
                val shown = exports.value
                if (shown == null) {
                    BrowserScreen(
                        state = state.value, onRefresh = {}, onToggleLayout = {}, onItemClick = {}, onItemLongClick = {},
                        onToggleSelection = {}, onClearSelection = {}, onSelectAll = {}, onExportSelected = {}, onOpenExports = {}, onPickFolder = {},
                    )
                } else {
                    ExportsScreen(jobs = shown.first, batch = shown.second, onBack = {}, onCancel = {}, onClearHistory = {})
                }
            }
        }
        compose.onNodeWithText("Connect a camera via USB").assertIsDisplayed()
        state.value = BrowserUiState(usbConnected = true, loading = false)
        compose.onNodeWithText("No photos or videos found").assertIsDisplayed()
        compose.onNodeWithText("Select USB folder").assertIsDisplayed()
        state.value = BrowserUiState(usbConnected = true, loading = false, items = listOf(video), selectedIds = setOf(video.id))
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithText("Select all").assertIsDisplayed()
        compose.onNodeWithText("Export (1)", useUnmergedTree = true).assertIsDisplayed()

        fun job(id: Long, status: ExportStatus) = ExportJob(
            id = id, batchId = 1, sourceId = "s$id", displayName = "$id.mp4", kind = MediaKind.VIDEO, sourceSizeBytes = 1, status = status,
        )
        exports.value = listOf(
            job(1, ExportStatus.QUEUED),
            job(2, ExportStatus.RUNNING).copy(progressPercent = 37),
            job(3, ExportStatus.FAILED).copy(failure = FailureReason.USB_REMOVED),
            job(4, ExportStatus.FAILED).copy(failure = FailureReason.OTHER, errorMessage = "read failed: EIO"),
        ) to ExportBatch(1, BatchStatus.ACTIVE)
        compose.onNodeWithText("Export status").assertIsDisplayed()
        compose.onNodeWithText("Waiting").assertIsDisplayed()
        compose.onNodeWithText("Exporting 37%").assertIsDisplayed()
        compose.onNodeWithText("The USB device was disconnected. Reconnect it and try again.").assertIsDisplayed()
        // An unclassified failure is still explained in words; the raw report is secondary.
        compose.onNodeWithText("An unexpected error occurred. Please try again.").assertIsDisplayed()
        compose.onNodeWithText("read failed: EIO").assertIsDisplayed()
        exports.value = emptyList<ExportJob>() to ExportBatch(1, BatchStatus.DONE, 5, 4, 1, 0, 1_780_000_000_000)
        compose.onNodeWithText("Export summary").assertIsDisplayed()
        compose.onNodeWithText("4 of 5 files exported, 1 failed").assertIsDisplayed()
        exports.value = emptyList<ExportJob>() to ExportBatch(1, BatchStatus.DONE, 5, 5, 0, 0, 1_780_000_000_000)
        compose.onNodeWithText("All 5 files exported").assertIsDisplayed()
        exports.value = emptyList<ExportJob>() to null
        compose.onNodeWithText("No export history").assertIsDisplayed()
    }

    @Test
    fun noJapaneseIsLeftInTheEnglishStrings() {
        val english = localized("en")
        for (id in ownKeys()) {
            val text = english.getString(id)
            assertFalse("${context.resources.getResourceEntryName(id)}: $text", text.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF || it.code in 0xAC00..0xD7A3 })
        }
    }

    private companion object {
        val LANGUAGES = listOf("en", "ja", "zh-CN", "zh-TW", "ko")

        /** Pure formats with no words in them. */
        val SAME_IN_EVERY_LANGUAGE = setOf("app_name", "joined")
    }
}
