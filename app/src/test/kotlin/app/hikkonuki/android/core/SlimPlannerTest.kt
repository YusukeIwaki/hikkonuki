package app.hikkonuki.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlimPlannerTest {
    private fun dji4k(videoMbps: Double, tenBit: Boolean = true) = VideoInfo(
        width = 3840, height = 2160, fps = 29.97, durationMillis = 41_608,
        videoBitrateBps = (videoMbps * 1_000_000).toLong(), audioBitrateBps = 317_000, tenBit = tenBit,
    )

    private fun spec(camera: CameraKind, info: VideoInfo) = (SlimPlanner.plan(camera, info) as VideoPlan.Transcode).spec

    @Test
    fun targetDimensionsCapLongEdgeWithoutUpscaling() {
        assertEquals(2560 to 1440, SlimPlanner.targetDimensions(3840, 2160))
        assertEquals(1440 to 2560, SlimPlanner.targetDimensions(2160, 3840))
        assertEquals(1920 to 1080, SlimPlanner.targetDimensions(1920, 1080))
        assertEquals(2560 to 1920, SlimPlanner.targetDimensions(4000, 3000))
        assertEquals(2560 to 1070, SlimPlanner.targetDimensions(3840, 1606))
        assertEquals(2560 to 1440, SlimPlanner.targetDimensions(7680, 4320))
    }

    @Test
    fun autoBitrateMatchesTheScript() {
        assertEquals(8_000_000, SlimPlanner.autoBitrateBps(1920, 1080, 29.97))
        assertEquals(12_000_000, SlimPlanner.autoBitrateBps(2560, 1440, 29.97))
        assertEquals(12_000_000, SlimPlanner.autoBitrateBps(1440, 2560, 30.0))
        assertEquals(25_000_000, SlimPlanner.autoBitrateBps(2560, 1440, 59.94))
        assertEquals(12_000_000, SlimPlanner.autoBitrateBps(2560, 1440, Double.NaN))
        assertEquals(750_000, SlimPlanner.autoBitrateBps(160, 90, 30.0))
    }

    @Test
    fun osmoPocketClipsLandNearTheHandTunedBitrates() {
        // Sources the script was happy to encode at 12 Mbps.
        assertEquals(12_000_000, spec(CameraKind.DJI, dji4k(34.0)).bitrateBps)
        assertTrue(spec(CameraKind.DJI, dji4k(36.5)).bitrateBps in 12_000_000..13_000_000)
        // 0005: 12 Mbps failed the SSIM check, 18 Mbps passed.
        assertTrue(spec(CameraKind.DJI, dji4k(38.2)).bitrateBps in 13_000_000..18_000_000)
        assertTrue(spec(CameraKind.DJI, dji4k(42.0)).bitrateBps in 17_000_000..21_000_000)
        // 0034: needed 40 Mbps.
        assertTrue(spec(CameraKind.DJI, dji4k(53.6)).bitrateBps in 38_000_000..44_000_000)
    }

    @Test
    fun osmoPocketClipsTooDetailedToShrinkAreKept() {
        assertEquals(VideoPlan.KeepOriginal(KeepReason.LOW_SAVINGS), SlimPlanner.plan(CameraKind.DJI, dji4k(58.0)))
    }

    @Test
    fun insta360IsNotBoostedByItsConstantBitrate() {
        val info = VideoInfo(width = 1920, height = 1080, fps = 29.97, durationMillis = 52_000, videoBitrateBps = 52_400_000, audioBitrateBps = 189_000)
        val spec = spec(CameraKind.INSTA360, info)
        assertEquals(1920, spec.width)
        assertEquals(1080, spec.height)
        assertEquals(8_000_000, spec.bitrateBps)
        assertEquals(16_000_000, spec.peakBitrateBps)
        assertFalse(spec.tenBit)
    }

    @Test
    fun tenBitRotationAndFrameRateCarryOver() {
        val spec = spec(CameraKind.DJI, dji4k(36.0).copy(rotationDegrees = 90, fps = 59.94, videoBitrateBps = 70_000_000))
        assertTrue(spec.tenBit)
        assertEquals(90, spec.rotationDegrees)
        assertEquals(59.94, spec.fps, 0.001)
        assertEquals(2560 to 1440, spec.width to spec.height)
    }

    @Test
    fun hdrAndUnreadableSourcesAreKept() {
        assertEquals(VideoPlan.KeepOriginal(KeepReason.HDR), SlimPlanner.plan(CameraKind.DJI, dji4k(40.0).copy(hdr = true)))
        assertEquals(VideoPlan.KeepOriginal(KeepReason.UNREADABLE), SlimPlanner.plan(CameraKind.UNKNOWN, VideoInfo(0, 0)))
    }

    @Test
    fun alreadySmallSourcesAreKept() {
        val info = VideoInfo(width = 1920, height = 1080, fps = 30.0, videoBitrateBps = 9_000_000, audioBitrateBps = 128_000)
        assertEquals(VideoPlan.KeepOriginal(KeepReason.LOW_SAVINGS), SlimPlanner.plan(CameraKind.UNKNOWN, info))
    }

    @Test
    fun unknownSourceBitrateStillConverts() {
        val spec = spec(CameraKind.UNKNOWN, VideoInfo(width = 3840, height = 2160, fps = 30.0))
        assertEquals(12_000_000, spec.bitrateBps)
    }

    @Test
    fun savingsGate() {
        assertTrue(SlimPlanner.savesEnough(1000, 800))
        assertFalse(SlimPlanner.savesEnough(1000, 801))
        assertTrue(SlimPlanner.savesEnough(0, 801))
    }
}
