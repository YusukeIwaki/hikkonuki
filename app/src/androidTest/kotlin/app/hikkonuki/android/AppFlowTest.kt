package app.hikkonuki.android

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.hikkonuki.android.core.MediaKind
import app.hikkonuki.android.platform.AppGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The whole app on a device: real activity, real foreground service, real codecs.
 * Camera-shaped files in the debug inbox stand in for the USB camera.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    private val photoName = "DJI_20261107113403_0001_D.JPG"
    private val videoName = "VID_20260510_132733_019.mp4"

    private val compose = createAndroidComposeRule<MainActivity>()

    // Files must be in place before the activity starts and scans.
    private val media = object : ExternalResource() {
        override fun before() {
            DeviceSupport.clearInbox()
            DeviceSupport.clearGallery()
            AppGraph.get(DeviceSupport.context).engine.clearHistory()
            DeviceSupport.installAsset("photo.jpg", photoName)
            DeviceSupport.installAsset("insta360_like_1080p.mp4", videoName)
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(media).around(compose)

    @After
    fun tearDown() {
        DeviceSupport.clearInbox()
        DeviceSupport.clearGallery()
    }

    @Test
    fun selectEverythingExportAndSeeTheResult() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("mediaGrid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mediaItem_$videoName").performTouchInput { longClick() }
        compose.onNodeWithTag("selectAll").performClick()
        compose.onNodeWithTag("exportSelectedFab").performClick()

        // Rows while it runs, then only the result.
        compose.onNodeWithTag("exportsList").assertIsDisplayed()
        compose.waitUntil(120_000) { compose.onAllNodesWithTag("exportsSummary").fetchSemanticsNodes().isNotEmpty() }
        // Worded in whatever language the device is set to.
        compose.onNodeWithText(DeviceSupport.context.resources.getQuantityString(R.plurals.result_all_succeeded, 2, 2)).assertIsDisplayed()

        assertNotNull(DeviceSupport.published(photoName, MediaKind.PHOTO))
        val video = DeviceSupport.published("VID_20260510_132733_019_slim.mp4", MediaKind.VIDEO)!!
        val expected = LocalDateTime.of(2026, 5, 10, 13, 27, 33).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, video.dateTakenMillis)

        compose.onNodeWithTag("clearExportHistory").performClick()
        compose.onNodeWithTag("exportsEmpty").assertIsDisplayed()
    }
}
