package app.hikkonuki.android

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hikkonuki.android.core.BatchStatus
import app.hikkonuki.android.core.ExportBatch
import app.hikkonuki.android.core.ExportJob
import app.hikkonuki.android.core.ExportOutcome
import app.hikkonuki.android.core.ExportStatus
import app.hikkonuki.android.core.FailureReason
import app.hikkonuki.android.core.MediaKind
import app.hikkonuki.android.theme.HikkonukiTheme
import app.hikkonuki.android.ui.BrowserLayout
import app.hikkonuki.android.ui.BrowserScreen
import app.hikkonuki.android.ui.BrowserUiState
import app.hikkonuki.android.ui.DetailScreen
import app.hikkonuki.android.ui.ExportsScreen
import app.hikkonuki.android.ui.MediaItem
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Renders every screen in every state and every language, and saves a screenshot of each:
 * the material for reviewing translations and layouts side by side.
 *
 * It is not a test of behaviour, so it only runs when asked for:
 *
 *     ./gradlew :app:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=app.hikkonuki.android.ScreenCatalogTest \
 *       -Pandroid.testInstrumentationRunnerArguments.screenshots=true
 *     adb pull /sdcard/Android/data/app.hikkonuki.android/files/screens
 *
 * The screens are the real composables fed with made-up state: nothing is exported and
 * no camera is needed.
 */
@RunWith(AndroidJUnit4::class)
class ScreenCatalogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val outDir get() = File(context.getExternalFilesDir(null), "screens")

    private var languageTag by mutableStateOf("en")
    private var screen by mutableStateOf<@Composable () -> Unit>({})

    private val day = 24L * 60 * 60 * 1000
    private val base = 1_795_760_000_000L // 2026-11-27

    private fun gradientJpeg(name: String, index: Int): File {
        val file = File(context.cacheDir, "catalog/$name").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val hue = (index * 47) % 360
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 1200f, 900f,
                android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.9f)),
                android.graphics.Color.HSVToColor(floatArrayOf((hue + 50f) % 360, 0.7f, 0.45f)),
                Shader.TileMode.CLAMP,
            )
        }
        Canvas(bitmap).drawRect(0f, 0f, 1200f, 900f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file
    }

    private fun media(): List<MediaItem> {
        val video = File(context.cacheDir, "catalog/clip.mp4").apply {
            parentFile!!.mkdirs()
            instrumentation.context.assets.open("insta360_like_1080p.mp4").use { input -> outputStream().use { input.copyTo(it) } }
        }
        val names = listOf(
            "DJI_20261127152905_0012_D.JPG", "DJI_20261127152850_0011_D.MP4", "DJI_20261127151203_0010_D.JPG",
            "DJI_20261127150412_0009_D.JPG", "VID_20261126_183355_00_008.mp4", "IMG_20261126_183101_00_007.jpg",
            "IMG_20261126_182944_00_006.jpg", "DJI_20261125101530_0005_D.JPG", "DJI_20261125101502_0004_D.MP4",
            "DJI_20261125095811_0003_D.JPG", "DJI_20261124174020_0002_D.JPG", "DJI_20261124173958_0001_D.JPG",
        )
        return names.mapIndexed { index, name ->
            val isVideo = name.endsWith("mp4", ignoreCase = true)
            val file = if (isVideo) video else gradientJpeg(name, index)
            MediaItem(
                id = "catalog://$name", uri = Uri.fromFile(file), displayName = name,
                kind = if (isVideo) MediaKind.VIDEO else MediaKind.PHOTO,
                sizeBytes = if (isVideo) 219_904_052L - index * 7_000_000L else 4_800_512L + index * 31_000L,
                dateTakenMillis = base - index * (day / 5),
                width = if (isVideo) 3840 else 4000, height = if (isVideo) 2160 else 3000,
                durationMillis = if (isVideo) 41_608L + index * 9_000L else null,
                mimeType = if (isVideo) "video/mp4" else "image/jpeg", sourceLabel = "USB",
            )
        }
    }

    private fun shoot(name: String, settleMillis: Long = 400) {
        compose.waitForIdle()
        // Thumbnails and detail images load off the main thread, outside Compose's idle tracking.
        Thread.sleep(settleMillis)
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = File(outDir, "$languageTag/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(content: @Composable () -> Unit) {
        screen = content
    }

    /**
     * Switches the language of the activity itself. Wrapping the content in a localized
     * context is not enough: menus and dialogs are separate windows that take their
     * resources from the activity, and would stay in the device language.
     */
    @Suppress("DEPRECATION")
    private fun useLanguage(tag: String) {
        val locale = Locale.forLanguageTag(tag)
        compose.runOnUiThread {
            Locale.setDefault(locale)
            for (resources in listOf(compose.activity.resources, compose.activity.applicationContext.resources)) {
                val configuration = Configuration(resources.configuration).apply { setLocales(android.os.LocaleList(locale)) }
                resources.updateConfiguration(configuration, resources.displayMetrics)
            }
            languageTag = tag
        }
    }

    private fun localizedString(id: Int): String = compose.activity.getString(id)

    @Composable
    private fun Browser(state: BrowserUiState) = BrowserScreen(
        state = state, onRefresh = {}, onToggleLayout = {}, onItemClick = {}, onItemLongClick = {}, onToggleSelection = {},
        onClearSelection = {}, onSelectAll = {}, onExportSelected = {}, onOpenExports = {}, onPickFolder = {},
    )

    @Composable
    private fun Detail(items: List<MediaItem>, index: Int, available: Boolean = true) = DetailScreen(
        items = items, initialIndex = index, exists = { available }, onShown = {}, onBack = {}, onExport = {},
    )

    @Composable
    private fun Exports(jobs: List<ExportJob>, batch: ExportBatch?) =
        ExportsScreen(jobs = jobs, batch = batch, onBack = {}, onCancel = {}, onClearHistory = {})

    private fun job(id: Long, name: String, status: ExportStatus, video: Boolean = name.endsWith("mp4", true)) = ExportJob(
        id = id, batchId = 1, sourceId = "s$id", displayName = name, kind = if (video) MediaKind.VIDEO else MediaKind.PHOTO,
        sourceSizeBytes = if (video) 219_904_052 else 4_800_512, status = status,
    )

    @Test
    fun captureEveryScreenInEveryLanguage() {
        assumeTrue("pass -Pandroid.testInstrumentationRunnerArguments.screenshots=true", InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        outDir.deleteRecursively()
        val items = media()
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            // The activity's own resources are switched (see useLanguage), as they are when the
            // system applies a language. Only the configuration local has to be refreshed by hand.
            val configuration = androidx.compose.runtime.remember(languageTag) { Configuration(compose.activity.resources.configuration) }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                HikkonukiTheme { androidx.compose.runtime.key(languageTag, screen) { screen() } }
            }
        }
        val connected = BrowserUiState(usbConnected = true, loading = false)
        val withMedia = connected.copy(items = items, deviceName = "Insta360 Ace Pro 2", watchedFolders = listOf("x"))

        for (tag in listOf("en", "ja", "zh-CN", "zh-TW", "ko")) {
            useLanguage(tag)

            show { Browser(BrowserUiState(usbConnected = false, loading = false)) }
            shoot("01_no_camera")
            show { Browser(connected) }
            shoot("02_no_media_first_time")
            show { Browser(connected.copy(watchedFolders = listOf("x"))) }
            shoot("03_no_media_in_saved_folder")
            show { Browser(connected.copy(folderPermissionError = true)) }
            shoot("04_folder_permission_error")
            show { Browser(withMedia) }
            shoot("05_grid", settleMillis = 2500)
            show { Browser(withMedia.copy(layout = BrowserLayout.LIST)) }
            shoot("06_list", settleMillis = 1200)
            show { Browser(connected.copy(deviceName = null)) }
            compose.onNodeWithContentDescription(localizedString(R.string.menu)).performClick()
            shoot("07_menu_with_folder_option")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            show { Browser(withMedia.copy(selectedIds = setOf(items[0].id, items[2].id, items[3].id))) }
            shoot("08_selection", settleMillis = 1200)

            show { Detail(items, 0) }
            shoot("09_detail_photo", settleMillis = 1500)
            show { Detail(items, 1) }
            shoot("10_detail_video", settleMillis = 2000)
            compose.onNodeWithTag("detailMenu").performClick()
            shoot("11_detail_menu")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            show { Detail(items.map { it.copy(width = 0, height = 0, durationMillis = null, dateTakenMillis = null, sizeBytes = 0) }, 1) }
            shoot("12_detail_unknown_values", settleMillis = 1500)
            show { Detail(items, 0, available = false) }
            shoot("13_detail_unavailable")

            show { Exports(emptyList(), null) }
            shoot("14_exports_empty")
            show {
                Exports(
                    listOf(
                        job(1, items[0].displayName, ExportStatus.COMPLETED).copy(outcome = ExportOutcome.COPIED, outputSizeBytes = 4_800_512),
                        job(2, items[1].displayName, ExportStatus.COMPLETED).copy(outcome = ExportOutcome.CONVERTED, outputSizeBytes = 87_296_455),
                        job(3, items[4].displayName, ExportStatus.RUNNING).copy(progressPercent = 37),
                        job(4, items[5].displayName, ExportStatus.QUEUED),
                        job(5, items[8].displayName, ExportStatus.QUEUED),
                    ),
                    ExportBatch(1, BatchStatus.ACTIVE),
                )
            }
            shoot("15_exports_running")
            show {
                Exports(
                    listOf(
                        job(1, items[0].displayName, ExportStatus.FAILED).copy(failure = FailureReason.USB_REMOVED),
                        job(2, items[1].displayName, ExportStatus.FAILED).copy(failure = FailureReason.NO_SPACE),
                        job(3, items[2].displayName, ExportStatus.FAILED).copy(failure = FailureReason.INCOMPLETE_COPY),
                    ),
                    ExportBatch(1, BatchStatus.ACTIVE),
                )
            }
            shoot("16_exports_failures_1")
            show {
                Exports(
                    listOf(
                        job(4, items[4].displayName, ExportStatus.FAILED).copy(failure = FailureReason.EMPTY_OUTPUT),
                        job(5, items[5].displayName, ExportStatus.FAILED).copy(failure = FailureReason.DESTINATION_UNAVAILABLE),
                        job(6, items[8].displayName, ExportStatus.FAILED).copy(failure = FailureReason.OTHER, errorMessage = "read failed: EIO (I/O error)"),
                    ),
                    ExportBatch(1, BatchStatus.ACTIVE),
                )
            }
            shoot("16_exports_failures_2")
            show { Exports(emptyList(), ExportBatch(1, BatchStatus.DONE, 12, 12, 0, base, base)) }
            shoot("17_exports_result_all_succeeded")
            show { Exports(emptyList(), ExportBatch(1, BatchStatus.DONE, 12, 9, 3, base, base)) }
            shoot("18_exports_result_with_failures")
        }
    }
}
