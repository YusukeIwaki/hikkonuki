package app.hikkonuki.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

class Mp4Test {
    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): File {
        val file = temp.newFile()
        javaClass.getResourceAsStream("/fixtures/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun metadata(file: File) = RandomAccessFile(file, "r").use { Mp4.readMetadata(FileSource(it)) }
    private fun topLevel(file: File) = RandomAccessFile(file, "r").use { Mp4.boxes(FileSource(it)).map { box -> box.type } }
    private fun utc(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun readsOsmoPocketContainer() {
        val meta = metadata(fixture("dji_skeleton.mp4"))
        assertEquals(utc("2026-11-07T02:34:03Z"), meta.creationTimeMillis)
        assertEquals("DJI OsmoPocket4P", meta.tags["encoder"])
        assertEquals("OsmoPocket4P", CameraDetector.djiModelFromEncoderTag(meta.tags["encoder"]))
        assertEquals("Rec.709", meta.tags["com.dji.camera.ColorGammaSxS"])
    }

    @Test
    fun readsInsta360ContainerAndTrailer() {
        val file = fixture("insta360_skeleton.mp4")
        assertEquals(listOf("ftyp", "wide", "mdat", "moov", "inst"), topLevel(file))
        assertEquals(utc("2026-05-10T04:27:33Z"), metadata(file).creationTimeMillis)
        assertEquals("Insta360 Ace Pro 2", CameraDetector.insta360ModelFromTail(file.readBytes().takeLast(4096).toByteArray()))
    }

    @Test
    fun stampsDateAndCameraIntoMuxerOutput() {
        val file = fixture("tiny_moov_last.mp4")
        val mediaBefore = mdat(file)
        val result = Mp4Stamper.stamp(file, Mp4Stamper.Stamp(utc("2026-05-05T02:44:20Z"), 9 * 3600, "Insta360", "Insta360 Ace Pro 2"))
        // mvhd + (tkhd + mdhd) for the video and audio tracks
        assertEquals(5, result.timesPatched)
        assertTrue(result.tagsWritten)
        val meta = metadata(file)
        assertEquals(utc("2026-05-05T02:44:20Z"), meta.creationTimeMillis)
        assertEquals("2026-05-05T11:44:20+0900", meta.tags["com.apple.quicktime.creationdate"])
        assertEquals("Insta360", meta.tags["com.apple.quicktime.make"])
        assertEquals("Insta360 Ace Pro 2", meta.tags["com.apple.quicktime.model"])
        assertEquals("Insta360", meta.tags["com.android.manufacturer"])
        assertEquals("Insta360 Ace Pro 2", meta.tags["com.android.model"])
        // The file is still one well-formed box sequence and the media bytes did not move.
        RandomAccessFile(file, "r").use { raf ->
            val boxes = Mp4.boxes(FileSource(raf))
            assertEquals(raf.length(), boxes.last().end)
            assertEquals("moov", boxes.last().type)
        }
        assertArrayEquals(mediaBefore, mdat(file))
    }

    @Test
    fun stampingTwiceReplacesInsteadOfDuplicating() {
        val file = fixture("tiny_moov_last.mp4")
        Mp4Stamper.stamp(file, Mp4Stamper.Stamp(utc("2026-05-05T02:44:20Z"), 0, "DJI", "A"))
        val sizeAfterFirst = file.length()
        Mp4Stamper.stamp(file, Mp4Stamper.Stamp(utc("2026-05-05T02:44:20Z"), 0, "DJI", "B"))
        assertEquals(sizeAfterFirst, file.length())
        assertEquals("B", metadata(file).tags["com.apple.quicktime.model"])
    }

    @Test
    fun keepsTagsTheMuxerAlreadyWrote() {
        val file = fixture("dji_skeleton.mp4")
        val result = Mp4Stamper.stamp(file, Mp4Stamper.Stamp(utc("2026-11-07T02:34:03Z"), 9 * 3600, "DJI", "OsmoPocket4P"))
        assertTrue(result.tagsWritten)
        val tags = metadata(file).tags
        assertEquals("DJI", tags["com.apple.quicktime.make"])
        assertEquals("Rec.709", tags["com.dji.camera.ColorGammaSxS"])
        assertEquals("20mm F2", tags["com.dji.camera.LensType"])
    }

    @Test
    fun whenMoovIsNotLastOnlyTheDatesArePatchedInPlace() {
        for (name in listOf("tiny_moov_first.mp4", "insta360_skeleton.mp4")) {
            val file = fixture(name)
            val before = file.readBytes()
            val result = Mp4Stamper.stamp(file, Mp4Stamper.Stamp(utc("2026-05-05T02:44:20Z"), 9 * 3600, "Insta360", "X"))
            assertTrue(name, result.timesPatched >= 3)
            assertFalse(name, result.tagsWritten)
            assertEquals(name, before.size.toLong(), file.length())
            assertEquals(name, utc("2026-05-05T02:44:20Z"), metadata(file).creationTimeMillis)
            // Only the 8 date bytes of each header changed.
            val after = file.readBytes()
            assertTrue(name, before.indices.count { before[it] != after[it] } <= result.timesPatched * 8)
        }
    }

    /** ftyp, moov, free(padding), mdat: what Android's muxer writes for short recordings. */
    private fun moovFirstWithPadding(padding: Int): File {
        val original = fixture("tiny_moov_last.mp4")
        val bytes = original.readBytes()
        val boxes = RandomAccessFile(original, "r").use { Mp4.boxes(FileSource(it)) }
        fun slice(type: String) = boxes.first { it.type == type }.let { bytes.copyOfRange(it.offset.toInt(), it.end.toInt()) }
        val free = java.nio.ByteBuffer.allocate(padding).putInt(padding).put("free".toByteArray()).array()
        return temp.newFile().apply { writeBytes(slice("ftyp") + slice("moov") + free + slice("mdat")) }
    }

    @Test
    fun tagsGoIntoThePaddingAfterMoovWithoutMovingTheMedia() {
        val file = moovFirstWithPadding(1327)
        val length = file.length()
        val mediaBefore = mdat(file)
        val mdatOffset = RandomAccessFile(file, "r").use { raf -> Mp4.boxes(FileSource(raf)).first { it.type == "mdat" }.offset }
        val result = Mp4Stamper.stamp(file, Mp4Stamper.Stamp(utc("2026-05-10T04:27:33Z"), 9 * 3600, "Insta360", "Insta360 Ace Pro 2"))
        assertTrue(result.tagsWritten)
        assertEquals(length, file.length())
        RandomAccessFile(file, "r").use { raf ->
            val boxes = Mp4.boxes(FileSource(raf))
            assertEquals(listOf("ftyp", "moov", "free", "mdat"), boxes.map { it.type })
            assertEquals(mdatOffset, boxes.last().offset)
            assertEquals(raf.length(), boxes.last().end)
        }
        assertArrayEquals(mediaBefore, mdat(file))
        val meta = metadata(file)
        assertEquals(utc("2026-05-10T04:27:33Z"), meta.creationTimeMillis)
        assertEquals("Insta360 Ace Pro 2", meta.tags["com.android.model"])
        assertEquals("2026-05-10T13:27:33+0900", meta.tags["com.apple.quicktime.creationdate"])
    }

    @Test
    fun paddingThatIsTooSmallLeavesTheFileUntouchedApartFromDates() {
        for (padding in listOf(8, 64)) {
            val file = moovFirstWithPadding(padding)
            val length = file.length()
            val result = Mp4Stamper.stamp(file, Mp4Stamper.Stamp(utc("2026-05-10T04:27:33Z"), 9 * 3600, "Insta360", "Insta360 Ace Pro 2"))
            assertFalse(result.tagsWritten)
            assertEquals(length, file.length())
            assertEquals(utc("2026-05-10T04:27:33Z"), metadata(file).creationTimeMillis)
            assertEquals(listOf("ftyp", "moov", "free", "mdat"), topLevel(file))
        }
    }

    @Test
    fun originalsCanBeDateFixedWithoutAddingTags() {
        val file = fixture("tiny_moov_last.mp4")
        val length = file.length()
        val result = Mp4Stamper.stamp(file, Mp4Stamper.Stamp(utc("2026-05-05T02:44:20Z"), 9 * 3600, "DJI", "X"), writeTags = false)
        assertFalse(result.tagsWritten)
        assertEquals(length, file.length())
        assertEquals(utc("2026-05-05T02:44:20Z"), metadata(file).creationTimeMillis)
    }

    @Test
    fun garbageIsLeftAlone() {
        val file = temp.newFile().apply { writeBytes(ByteArray(1000) { (it * 7).toByte() }) }
        val before = file.readBytes()
        assertEquals(Mp4Stamper.Result(0, false), Mp4Stamper.stamp(file, Mp4Stamper.Stamp(utc("2026-05-05T02:44:20Z"))))
        assertArrayEquals(before, file.readBytes())
        assertNull(metadata(file).creationTimeMillis)
        assertNull(metadata(temp.newFile()).creationTimeMillis)
    }

    @Test
    fun unsetCreationTimeReadsAsNull() {
        val file = fixture("tiny_moov_last.mp4")
        Mp4Stamper.stamp(file, Mp4Stamper.Stamp(-Mp4.QT_EPOCH_OFFSET_SECONDS * 1000), writeTags = false)
        assertNull(metadata(file).creationTimeMillis)
    }

    private fun mdat(file: File): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val box = Mp4.boxes(FileSource(raf)).first { it.type == "mdat" }
        ByteArray(box.size.toInt()).also { raf.seek(box.offset); raf.readFully(it) }
    }
}
