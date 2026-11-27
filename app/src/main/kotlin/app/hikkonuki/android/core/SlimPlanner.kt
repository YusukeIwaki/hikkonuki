package app.hikkonuki.android.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** What the platform probe learned about a source video. Dimensions are coded (unrotated). */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val fps: Double = 30.0,
    val durationMillis: Long = 0,
    /** Video track bitrate; 0 when the container does not say. */
    val videoBitrateBps: Long = 0,
    val audioBitrateBps: Long = 0,
    val mime: String = "video/hevc",
    val tenBit: Boolean = false,
    val hdr: Boolean = false,
    val hasAudio: Boolean = true,
)

data class TranscodeSpec(
    val width: Int,
    val height: Int,
    val bitrateBps: Int,
    val peakBitrateBps: Int,
    val fps: Double,
    val tenBit: Boolean,
    val rotationDegrees: Int,
    val durationMillis: Long,
)

sealed interface VideoPlan {
    data class Transcode(val spec: TranscodeSpec) : VideoPlan
    data class KeepOriginal(val reason: KeepReason) : VideoPlan
}

/** Why a video is imported as the camera wrote it instead of being converted. */
enum class KeepReason {
    /** HDR or Log footage needs a colour-managed workflow this conversion does not do. */
    HDR,
    /** Re-encoding would not make the file at least 20% smaller. */
    LOW_SAVINGS,
    /** The video stream could not be read. */
    UNREADABLE,
    /** Every converter failed; the point is still to get the video onto the phone. */
    CONVERSION_FAILED,
}

/**
 * The decisions of the ffmpeg scripts this conversion was first tuned with on a Mac,
 * kept as plain functions so they can be unit tested without a device:
 * long edge capped at 2560 (never upscaled, even dimensions), HEVC at a bitrate chosen from
 * the output pixel count and frame rate, 10-bit kept, HDR/Log left alone, and no re-encode
 * at all when it would not save at least 20%.
 */
object SlimPlanner {
    const val MAX_LONG_EDGE = 2560
    const val MIN_SAVINGS = 0.20

    fun targetDimensions(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val ratio = min(1.0, maxLongEdge.toDouble() / max(width, height))
        return max(2, (width * ratio).toInt() / 2 * 2) to max(2, (height * ratio).toInt() / 2 * 2)
    }

    private fun autoBitrateRaw(width: Int, height: Int, fps: Double): Double {
        val safeFps = if (fps.isFinite() && fps > 0) fps else 30.0
        val pixels = width.toDouble() * height
        return 8_000_000.0 * (pixels / (1920.0 * 1080.0)).pow(0.75) * max(1.0, safeFps / 30.0)
    }

    /** 8 Mbps at 1080p30 and 12 Mbps at 1440p30. */
    fun autoBitrateBps(width: Int, height: Int, fps: Double): Int =
        max(750_000, (autoBitrateRaw(width, height, fps) / 1_000_000).roundToInt() * 1_000_000)

    /**
     * The desktop workflow re-encoded samples and raised the bitrate (12 -> 18 -> 27 -> 40 Mbps)
     * until SSIM passed. Measuring SSIM on the phone would double the export time, so this
     * uses what that tuning revealed instead: the Osmo Pocket's own VBR encoder already
     * spends more bits on exactly the clips that needed the higher steps. Calibrated on the
     * tuned 4K30 clips: ~37 Mbps sources were fine at 12 Mbps, ~42 Mbps wanted 18,
     * ~54 Mbps wanted 40. Insta360 records at a near-constant bitrate, which says nothing
     * about the content, so only DJI gets the boost.
     */
    fun complexityMultiplier(camera: CameraKind, info: VideoInfo): Double {
        if (camera != CameraKind.DJI || info.videoBitrateBps <= 0) return 1.0
        val typical = 1.6 * autoBitrateRaw(info.width, info.height, info.fps)
        return (info.videoBitrateBps / typical).pow(3.0).coerceIn(1.0, 4.0)
    }

    fun plan(camera: CameraKind, info: VideoInfo): VideoPlan {
        if (info.width <= 0 || info.height <= 0) return VideoPlan.KeepOriginal(KeepReason.UNREADABLE)
        if (info.hdr) return VideoPlan.KeepOriginal(KeepReason.HDR)
        val (width, height) = targetDimensions(info.width, info.height)
        val base = autoBitrateRaw(width, height, info.fps) * complexityMultiplier(camera, info)
        val bitrate = max(750_000, (base / 1_000_000).roundToInt() * 1_000_000)
        val sourceTotal = info.videoBitrateBps + info.audioBitrateBps
        if (info.videoBitrateBps > 0 && bitrate + info.audioBitrateBps > sourceTotal * (1 - MIN_SAVINGS)) {
            return VideoPlan.KeepOriginal(KeepReason.LOW_SAVINGS)
        }
        return VideoPlan.Transcode(
            TranscodeSpec(
                width = width,
                height = height,
                bitrateBps = bitrate,
                peakBitrateBps = bitrate * 2,
                fps = if (info.fps.isFinite() && info.fps > 0) info.fps else 30.0,
                tenBit = info.tenBit,
                rotationDegrees = info.rotationDegrees,
                durationMillis = info.durationMillis,
            ),
        )
    }

    /** After encoding: a result that is not at least 20% smaller is not worth the quality loss. */
    fun savesEnough(sourceBytes: Long, outputBytes: Long): Boolean =
        sourceBytes <= 0 || outputBytes <= sourceBytes * (1 - MIN_SAVINGS)
}
