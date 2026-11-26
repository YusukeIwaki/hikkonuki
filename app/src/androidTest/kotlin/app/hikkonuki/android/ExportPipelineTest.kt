package app.hikkonuki.android

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hikkonuki.android.DeviceSupport.context
import app.hikkonuki.android.core.ExportEngine
import app.hikkonuki.android.core.ExportJob
import app.hikkonuki.android.core.ExportOutcome
import app.hikkonuki.android.core.ExportQueue
import app.hikkonuki.android.core.ExportRequest
import app.hikkonuki.android.core.ExportStatus
import app.hikkonuki.android.core.FallbackTranscoder
import app.hikkonuki.android.core.FileSource
import app.hikkonuki.android.core.MediaKind
import app.hikkonuki.android.core.Mp4
import app.hikkonuki.android.core.TranscodeSpec
import app.hikkonuki.android.core.VideoTranscoder
import app.hikkonuki.android.platform.AndroidSource
import app.hikkonuki.android.platform.AndroidVideoProber
import app.hikkonuki.android.platform.DirectVideoTranscoder
import app.hikkonuki.android.platform.Media3VideoTranscoder
import app.hikkonuki.android.platform.MediaStoreGallery
import app.hikkonuki.android.platform.SqliteJobPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Runs the real export pipeline (codecs, GPU scaler, muxer, MediaStore) on a device,
 * with camera-shaped clips standing in for a USB camera. No UI and no service involved.
 */
@RunWith(AndroidJUnit4::class)
class ExportPipelineTest {
    private val source = AndroidSource(context)
    private val gallery = MediaStoreGallery(context)
    private val direct10 = DirectVideoTranscoder(source, tenBit = true)
    private val direct8 = DirectVideoTranscoder(source, tenBit = false)
    private val media3 = Media3VideoTranscoder(context, source)

    private val osmoName = "DJI_20261107113403_0005_D.MP4"
    private val instaName = "VID_20260510_132733_019.mp4"

    @Before
    fun setUp() {
        DeviceSupport.clearInbox()
        DeviceSupport.clearGallery()
    }

    @After
    fun tearDown() {
        DeviceSupport.clearInbox()
        DeviceSupport.clearGallery()
    }

    private val persistence = RecordingPersistence(SqliteJobPersistence(context, null))

    private fun engine(transcoder: VideoTranscoder = FallbackTranscoder(listOf(direct10, direct8, media3))) = ExportEngine(
        queue = ExportQueue(persistence),
        source = source, prober = AndroidVideoProber(source), transcoder = transcoder, gallery = gallery, io = Dispatchers.IO,
    )

    private fun export(file: File, kind: MediaKind, engine: ExportEngine = engine()): ExportJob = runBlocking {
        engine.enqueue(listOf(ExportRequest(file.absolutePath, file.name, kind, file.length())))
        engine.drain()
        // The batch is done, so its rows are gone and only the summary is left.
        assertTrue(engine.queue.jobs.value.isEmpty())
        assertEquals(app.hikkonuki.android.core.BatchStatus.DONE, engine.queue.batches.value.first().status)
        persistence.lastState.values.last().also { Log.i("HikkonukiTest", "job: $it") }
    }

    /**
     * Audio is copied, not re-encoded: the same packets come out. The one allowed
     * difference is an encoder priming packet with a negative timestamp, which MP4 cannot
     * place and which is silent by definition (ffmpeg-made test clips have one).
     */
    private fun assertAudioCopied(input: List<DeviceSupport.Track>, output: List<DeviceSupport.Track>) {
        val before = input.single { it.mime.startsWith("audio/") }
        val after = output.single { it.mime.startsWith("audio/") }
        assertEquals(before.mime, after.mime)
        assertTrue("samples ${before.samples} -> ${after.samples}", before.samples - after.samples in 0..1)
        assertTrue("bytes ${before.bytes} -> ${after.bytes}", before.bytes - after.bytes in 0..1024)
    }

    private fun retrieverDate(path: String): String? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        } finally {
            retriever.release()
        }
    }

    private fun hevcDecoder(profile: Int) = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
        !codec.isEncoder && codec.supportedTypes.any { it.equals("video/hevc", true) } &&
            codec.getCapabilitiesForType("video/hevc").profileLevels.any { it.profile == profile }
    }

    private fun hevcEncoder(width: Int, height: Int, profile: Int) = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
        codec.isEncoder && codec.supportedTypes.any { it.equals("video/hevc", true) } &&
            codec.getCapabilitiesForType("video/hevc").let { caps ->
                caps.videoCapabilities.isSizeSupported(width, height) && caps.profileLevels.any { it.profile == profile }
            }
    }

    /** True on a phone; false on emulators, whose HEVC encoder stops at 512x512. */
    private val canEncode2k get() = hevcEncoder(2560, 1440, CodecProfileLevel.HEVCProfileMain)
    private val canDecodeTenBit get() = hevcDecoder(CodecProfileLevel.HEVCProfileMain10)

    @Test
    fun osmoPocketClipIsImportedWithItsDate() {
        val input = DeviceSupport.installAsset("osmo_like_4k_10bit.mp4", osmoName)
        val job = export(input, MediaKind.VIDEO)
        assertEquals(job.errorMessage, ExportStatus.COMPLETED, job.status)
        val expected = Instant.parse("2026-11-07T02:34:03Z").toEpochMilli()
        if (!canEncode2k || !canDecodeTenBit) {
            // This device cannot convert the clip: it must still arrive, as the original.
            assertEquals(job.detail, ExportOutcome.ORIGINAL, job.outcome)
            val output = DeviceSupport.published(osmoName, MediaKind.VIDEO)!!
            assertArrayEquals(input.readBytes(), File(output.path).readBytes())
            assertEquals(expected, output.dateTakenMillis)
            return
        }
        assertEquals(job.detail, ExportOutcome.CONVERTED, job.outcome)
        val output = DeviceSupport.published("DJI_20261107113403_0005_D_slim.mp4", MediaKind.VIDEO)!!
        assertEquals(job.outputSizeBytes, output.size)
        assertTrue("saved at least 20%", output.size <= input.length() * 0.8)

        val inputTracks = DeviceSupport.tracks(input.path)
        val tracks = DeviceSupport.tracks(output.path)
        val video = tracks.single { it.mime.startsWith("video/") }
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, video.mime)
        assertEquals(2560, video.format.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(1440, video.format.getInteger(MediaFormat.KEY_HEIGHT))
        // Every frame made it through, in the same count as the source.
        assertEquals(inputTracks.single { it.mime.startsWith("video/") }.samples, video.samples)
        assertAudioCopied(inputTracks, tracks)

        // The capture date is in the file, and the system's scan adopted it.
        val meta = RandomAccessFile(output.path, "r").use { Mp4.readMetadata(FileSource(it)) }
        assertEquals(expected, meta.creationTimeMillis)
        assertEquals("DJI", meta.tags["com.android.manufacturer"])
        assertTrue(retrieverDate(output.path)!!.startsWith("20261107T023403"))
        assertEquals(expected, output.dateTakenMillis)
        assertTrue(job.detail!!.contains("2560×1440"))
        if (hevcEncoder(2560, 1440, CodecProfileLevel.HEVCProfileMain10)) {
            assertTrue("10-bit kept: ${job.detail}", job.detail!!.contains("10bit"))
        }
    }

    @Test
    fun insta360ClipWithoutContainerDateGetsItFromTheFileName() {
        val input = DeviceSupport.installAsset("insta360_like_1080p.mp4", instaName)
        assertNull(RandomAccessFile(input, "r").use { Mp4.readMetadata(FileSource(it)) }.creationTimeMillis)
        val job = export(input, MediaKind.VIDEO)
        assertEquals(job.errorMessage, ExportStatus.COMPLETED, job.status)
        assertEquals(job.detail, ExportOutcome.CONVERTED, job.outcome)

        val output = DeviceSupport.published("VID_20260510_132733_019_slim.mp4", MediaKind.VIDEO)!!
        val video = DeviceSupport.tracks(output.path).single { it.mime.startsWith("video/") }
        // Already within 2K: resolution is kept, only the bitrate drops.
        assertEquals(1920 to 1080, video.format.getInteger(MediaFormat.KEY_WIDTH) to video.format.getInteger(MediaFormat.KEY_HEIGHT))
        assertTrue(output.size <= input.length() * 0.5)
        val inputTracks = DeviceSupport.tracks(input.path)
        val tracks = DeviceSupport.tracks(output.path)
        assertEquals(inputTracks.single { it.mime.startsWith("video/") }.samples, video.samples)
        assertAudioCopied(inputTracks, tracks)

        val expected = LocalDateTime.of(2026, 5, 10, 13, 27, 33).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val meta = RandomAccessFile(output.path, "r").use { Mp4.readMetadata(FileSource(it)) }
        RandomAccessFile(output.path, "r").use { raf ->
            val top = Mp4.boxes(FileSource(raf))
            Log.i("HikkonukiTest", "layout: ${top.map { "${it.type}@${it.offset}+${it.size}" }} length=${raf.length()}")
            val moov = top.first { it.type == "moov" }
            Log.i("HikkonukiTest", "moov: ${Mp4.boxes(FileSource(raf), moov.payloadOffset, moov.end).map { "${it.type}+${it.size}" }} tags=${meta.tags}")
        }
        assertEquals(expected, meta.creationTimeMillis)
        assertEquals("Insta360", meta.tags["com.android.manufacturer"])
        assertEquals(expected, output.dateTakenMillis)
    }

    @Test
    fun photoIsCopiedUnchanged() {
        val input = DeviceSupport.installAsset("photo.jpg", "DJI_20261107113403_0001_D.JPG")
        val job = export(input, MediaKind.PHOTO)
        assertEquals(job.errorMessage, ExportStatus.COMPLETED, job.status)
        val output = DeviceSupport.published(input.name, MediaKind.PHOTO)!!
        assertArrayEquals(input.readBytes(), File(output.path).readBytes())
    }

    @Test
    fun exportingTwiceDoesNotCreateASecondCopy() {
        val input = DeviceSupport.installAsset("insta360_like_1080p.mp4", instaName)
        assertEquals(ExportOutcome.CONVERTED, export(input, MediaKind.VIDEO).outcome)
        val firstCopy = DeviceSupport.published("VID_20260510_132733_019_slim.mp4", MediaKind.VIDEO)!!
        assertEquals(ExportOutcome.ALREADY_IMPORTED, export(input, MediaKind.VIDEO).outcome)
        assertEquals(firstCopy, DeviceSupport.published("VID_20260510_132733_019_slim.mp4", MediaKind.VIDEO))
    }

    @Test
    fun cancellingMidExportLeavesNothingBehind(): Unit = runBlocking {
        val input = DeviceSupport.installAsset("insta360_like_1080p.mp4", instaName)
        lateinit var engine: ExportEngine
        // Cancel from inside the first progress callback: the pipeline is fully running.
        val cancelling = object : VideoTranscoder {
            override val name = "cancelling"
            override suspend fun transcode(sourceId: String, spec: TranscodeSpec, output: File, onProgress: (Float) -> Unit): String =
                direct8.transcode(sourceId, spec, output) { engine.cancel(engine.queue.jobs.value.single().id) }
        }
        engine = engine(cancelling)
        engine.enqueue(listOf(ExportRequest(input.absolutePath, input.name, MediaKind.VIDEO, input.length())))
        engine.drain()
        assertTrue(engine.queue.jobs.value.isEmpty())
        assertTrue(engine.queue.batches.value.isEmpty())
        assertNull(DeviceSupport.published("VID_20260510_132733_019_slim.mp4", MediaKind.VIDEO))
        assertNull(DeviceSupport.published(instaName, MediaKind.VIDEO))
        val leftovers = File("/storage/emulated/0/${MediaStoreGallery.RELATIVE_PATH}").list().orEmpty().filter { it.contains("132733") }
        assertTrue("leftovers: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun halfWrittenFilesFromAKilledProcessAreRemovedAndInvisible() {
        val pending = gallery.create("orphan_slim.mp4", MediaKind.VIDEO)
        pending.file.writeBytes(ByteArray(4096))
        assertNull(gallery.findExisting("orphan_slim.mp4", MediaKind.VIDEO))
        gallery.discardStale()
        assertFalse(pending.file.exists())
        assertNull(DeviceSupport.published("orphan_slim.mp4", MediaKind.VIDEO))
    }

    private fun assertTranscodes(transcoder: VideoTranscoder, asset: String, name: String, tenBit: Boolean, width: Int, height: Int, frames: Int = 60, minProgress: Float = 0.8f): Unit = runBlocking {
        val input = DeviceSupport.installAsset(asset, name)
        val output = File(context.cacheDir, "${transcoder.name}.mp4").apply { delete() }
        val spec = TranscodeSpec(width, height, 8_000_000, 16_000_000, 29.97, tenBit, 0, frames * 1001L / 30)
        var lastProgress = 0f
        val description = transcoder.transcode(input.absolutePath, spec, output) { lastProgress = it }
        Log.i("HikkonukiTest", "${transcoder.name}: $description, ${output.length()} bytes")
        val video = DeviceSupport.tracks(output.path).single { it.mime.startsWith("video/") }
        assertEquals(width to height, video.format.getInteger(MediaFormat.KEY_WIDTH) to video.format.getInteger(MediaFormat.KEY_HEIGHT))
        assertTrue("frames: ${video.samples}", abs(video.samples - frames) <= 2)
        assertTrue("progress: $lastProgress", lastProgress > minProgress)
        // 8 Mbps target; allow generous slack for rate control.
        assertTrue("size: ${output.length()}", output.length() in frames * 5_000L..frames * 100_000L)
        output.delete()
        Unit
    }

    @Test
    fun directPipelineDownscalesOnTheGpu() =
        assertTranscodes(direct8, "osmo_like_4k_8bit.mp4", "DJI_20261108161105_0034_D.MP4", false, 1920, 1080, frames = 30)

    @Test
    fun directPipelineKeepsResolutionWhenNoScalingIsNeeded() = assertTranscodes(direct8, "insta360_like_1080p.mp4", instaName, false, 1920, 1080)

    @Test
    fun media3FallbackWorks() =
        // Its progress is polled, so a two second clip is only sampled a few times.
        assertTranscodes(media3, "insta360_like_1080p.mp4", instaName, false, 1920, 1080, minProgress = 0f)

    /** The real target: 4K 10-bit to 2K HEVC Main10. Needs phone hardware; skipped on emulators. */
    @Test
    fun directPipelineTenBitTwoK() {
        assumeTrue("no 2K HEVC Main10 encoder on this device", hevcEncoder(2560, 1440, CodecProfileLevel.HEVCProfileMain10) && canDecodeTenBit)
        assertTranscodes(direct10, "osmo_like_4k_10bit.mp4", osmoName, true, 2560, 1440)
    }

    /**
     * Converts a clip of flat colour patches and reads them back. Catches range and matrix
     * mistakes (washed out or crushed pictures) that no size or frame-count check would.
     */
    private fun assertColorsSurvive(transcoder: VideoTranscoder, asset: String, tenBit: Boolean): Unit = runBlocking {
        val input = DeviceSupport.installAsset(asset, "colors.mp4")
        val output = File(context.cacheDir, "colors_out.mp4").apply { delete() }
        transcoder.transcode(input.absolutePath, TranscodeSpec(1200, 720, 4_000_000, 8_000_000, 30.0, tenBit, 0, 1000), output) {}
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(output.path)
            retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        } finally {
            retriever.release()
        }
        val expected = listOf(0x282828, 0x808080, 0xDCDCDC, 0xC83232, 0x32C832, 0x3232C8)
        val report = StringBuilder()
        var worstGrey = 0
        var worstColor = 0
        expected.forEachIndexed { index, rgb ->
            val pixel = frame.getPixel(frame.width * (2 * index + 1) / 12, frame.height / 2)
            val want = intArrayOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
            val got = intArrayOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
            val error = (0..2).maxOf { abs(want[it] - got[it]) }
            if (index < 3) worstGrey = maxOf(worstGrey, error) else worstColor = maxOf(worstColor, error)
            report.append(String.format("#%06X->#%06X ", rgb, pixel and 0xFFFFFF))
        }
        Log.i("HikkonukiTest", "colours ${transcoder.name} $asset: $report grey=$worstGrey colour=$worstColor")
        output.delete()
        // Greys show range errors (a range mix-up moves #282828 by about 20).
        assertTrue("greys: $report", worstGrey <= 8)
        // Saturated patches show matrix errors. The emulator's own codecs mix BT.601 and
        // BT.709 (Media3 output is identical there), so only a phone is held to a tight bound.
        assertTrue("colours: $report", worstColor <= if (isEmulator) 30 else 12)
    }

    private val isEmulator get() = android.os.Build.HARDWARE in setOf("ranchu", "goldfish")

    @Test
    fun limitedRangeColorsSurvive() = assertColorsSurvive(direct8, "colors_limited_range.mp4", false)

    /**
     * The Insta360 case. Emulators convert full-range video as if it were limited range
     * (their GPU path ignores the flag, with Media3 just the same), so this runs on phones.
     */
    @Test
    fun fullRangeColorsSurvive() {
        assumeTrue("emulator decoders mishandle full-range video", !isEmulator)
        assertColorsSurvive(direct8, "colors_full_range.mp4", false)
    }

    @Test
    fun tenBitColorsSurvive() {
        assumeTrue("no HEVC Main10 codecs on this device", hevcEncoder(1200, 720, CodecProfileLevel.HEVCProfileMain10) && canDecodeTenBit)
        assertColorsSurvive(direct10, "colors_limited_range_10bit.mp4", true)
    }

    @Test
    fun proberReadsWhatThePlannerNeeds() {
        val input = DeviceSupport.installAsset("osmo_like_4k_10bit.mp4", osmoName)
        val probed = AndroidVideoProber(source).probe(input.absolutePath, app.hikkonuki.android.core.CameraKind.DJI)
        assertEquals(3840 to 2160, probed.info.width to probed.info.height)
        assertTrue(probed.info.tenBit)
        assertFalse(AndroidVideoProber(source).probe(DeviceSupport.installAsset("insta360_like_1080p.mp4", instaName).absolutePath, app.hikkonuki.android.core.CameraKind.INSTA360).info.tenBit)
        assertFalse(probed.info.hdr)
        assertTrue(probed.info.hasAudio)
        assertEquals(29.97, probed.info.fps, 0.5)
        assertTrue("bitrate ${probed.info.videoBitrateBps}", probed.info.videoBitrateBps in 20_000_000..60_000_000)
        assertEquals(Instant.parse("2026-11-07T02:34:03Z").toEpochMilli(), probed.containerCreationMillis)
        assertNotNull(probed.info.durationMillis.takeIf { it in 1900..2100 })
    }
}
