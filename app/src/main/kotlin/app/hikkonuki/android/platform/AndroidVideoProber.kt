package app.hikkonuki.android.platform

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import app.hikkonuki.android.core.CameraDetector
import app.hikkonuki.android.core.CameraKind
import app.hikkonuki.android.core.HevcConfig
import app.hikkonuki.android.core.Mp4
import app.hikkonuki.android.core.ProbedVideo
import app.hikkonuki.android.core.VideoInfo
import app.hikkonuki.android.core.VideoProber
import kotlin.math.min

/** Codec facts come from MediaExtractor; dates and camera tags from our own MP4 reader. */
class AndroidVideoProber(private val source: AndroidSource) : VideoProber {

    override fun probe(sourceId: String, camera: CameraKind): ProbedVideo {
        var model: String? = null
        var creation: Long? = null
        var log = false
        var fileSize = 0L
        runCatching {
            source.withRandomAccess(sourceId) { file ->
                fileSize = file.size
                val metadata = Mp4.readMetadata(file)
                creation = metadata.creationTimeMillis
                model = CameraDetector.djiModelFromEncoderTag(metadata.tags["encoder"])
                log = metadata.tags["com.dji.camera.ColorGammaSxS"]?.contains("log", ignoreCase = true) == true
                if (model == null && camera == CameraKind.INSTA360) {
                    val length = min(64 * 1024L, file.size).toInt()
                    val tail = ByteArray(length).also { file.read(file.size - length, it, 0, length) }
                    model = CameraDetector.insta360ModelFromTail(tail)
                }
            }
        }
        val info = readTracks(sourceId, fileSize)
        return ProbedVideo(info.copy(hdr = info.hdr || log), creation, model)
    }

    private fun readTracks(sourceId: String, fileSize: Long): VideoInfo {
        val extractor = MediaExtractor()
        try {
            source.setDataSource(extractor, sourceId)
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (video == null && mime.startsWith("video/") && mime != "video/mjpeg") video = format
                if (audio == null && mime.startsWith("audio/")) audio = format
            }
            val format = video ?: return VideoInfo(0, 0)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationMillis = format.long(MediaFormat.KEY_DURATION) / 1000
            val audioBitrate = audio?.int(MediaFormat.KEY_BIT_RATE)?.toLong()?.takeIf { it > 0 }
                ?: if (audio != null) 192_000L else 0L
            // Camera files are almost entirely video, so the file size is a good estimate
            // when the track does not declare its bitrate.
            val videoBitrate = format.int(MediaFormat.KEY_BIT_RATE).toLong().takeIf { it > 0 }
                ?: if (durationMillis > 0 && fileSize > 0) (fileSize * 8000 / durationMillis - audioBitrate).coerceAtLeast(0) else 0L
            val transfer = format.int(MediaFormat.KEY_COLOR_TRANSFER)
            return VideoInfo(
                width = format.int(MediaFormat.KEY_WIDTH),
                height = format.int(MediaFormat.KEY_HEIGHT),
                rotationDegrees = format.int(MediaFormat.KEY_ROTATION),
                fps = frameRate(format),
                durationMillis = durationMillis,
                videoBitrateBps = videoBitrate,
                audioBitrateBps = audioBitrate,
                mime = mime,
                tenBit = isTenBit(format, mime),
                hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG ||
                    mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION,
                hasAudio = audio != null,
            )
        } finally {
            extractor.release()
        }
    }

    private fun isTenBit(format: MediaFormat, mime: String): Boolean {
        if (mime != MediaFormat.MIMETYPE_VIDEO_HEVC) return false
        when (format.int(MediaFormat.KEY_PROFILE)) {
            CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10,
            CodecProfileLevel.HEVCProfileMain10HDR10Plus -> return true
            CodecProfileLevel.HEVCProfileMain -> return false
        }
        val csd = format.getByteBuffer("csd-0") ?: return false
        val bytes = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
        return HevcConfig.profileIdc(bytes) == HevcConfig.PROFILE_MAIN_10
    }

    private fun frameRate(format: MediaFormat): Double {
        if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) return 30.0
        val value = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
            .recoverCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
            .getOrDefault(30.0)
        return if (value > 0) value else 30.0
    }
}

internal fun MediaFormat.int(key: String): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

internal fun MediaFormat.long(key: String): Long =
    if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(0L) else 0L
