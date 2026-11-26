package app.hikkonuki.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CameraAndDateTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val now = Instant.parse("2026-11-27T00:00:00Z").toEpochMilli()
    private fun utc(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun detectsCameraFromFileName() {
        assertEquals(CameraKind.DJI, CameraDetector.fromFileName("DJI_20261107113403_0005_D.MP4"))
        assertEquals(CameraKind.DJI, CameraDetector.fromFileName("DJI_20261107113403_0005_D.JPG"))
        assertEquals(CameraKind.INSTA360, CameraDetector.fromFileName("VID_20260505_114420_009.mp4"))
        assertEquals(CameraKind.INSTA360, CameraDetector.fromFileName("VID_20260322_095529_00_001.mp4"))
        assertEquals(CameraKind.INSTA360, CameraDetector.fromFileName("IMG_20260505_114420_00_012.jpg"))
        assertEquals(CameraKind.UNKNOWN, CameraDetector.fromFileName("PXL_20260505_114420123.mp4"))
        assertEquals(CameraKind.UNKNOWN, CameraDetector.fromFileName("holiday.mp4"))
    }

    @Test
    fun readsModels() {
        assertEquals("OsmoPocket4P", CameraDetector.djiModelFromEncoderTag("DJI OsmoPocket4P"))
        assertNull(CameraDetector.djiModelFromEncoderTag("Lavf62.12.100"))
        assertNull(CameraDetector.djiModelFromEncoderTag(null))
        val tail = "\u0000\u0001IBGLA\u0012Insta360 Ace Pro 2\u0012\u0010v_2.0.24_build1*".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Insta360 Ace Pro 2", CameraDetector.insta360ModelFromTail(tail))
        assertNull(CameraDetector.insta360ModelFromTail(ByteArray(64)))
    }

    @Test
    fun djiContainerTimeIsKeptEvenWhenOneSecondOffTheName() {
        // DJI_...124028 with creation_time 03:40:29Z: the README case, existing value wins.
        val date = CaptureDateResolver.resolve(CameraKind.DJI, "DJI_20261107124028_0007_D.MP4", utc("2026-11-07T03:40:29Z"), tokyo, now)!!
        assertEquals(utc("2026-11-07T03:40:29Z"), date.epochMillis)
        assertEquals(DateSource.CONTAINER, date.source)
        assertEquals(9 * 3600, date.utcOffsetSeconds)
    }

    @Test
    fun insta360ContainerInRealUtcIsTrusted() {
        val date = CaptureDateResolver.resolve(CameraKind.INSTA360, "VID_20260505_114420_009.mp4", utc("2026-05-05T02:44:20Z"), tokyo, now)!!
        assertEquals(utc("2026-05-05T02:44:20Z"), date.epochMillis)
        assertEquals(DateSource.CONTAINER, date.source)
    }

    @Test
    fun cameraZoneIsTakenFromTheFileEvenAfterThePhoneMoved() {
        // Shot in Paris (UTC+2), imported on a phone that is back in Tokyo.
        val date = CaptureDateResolver.resolve(CameraKind.INSTA360, "VID_20260801_180000_001.mp4", utc("2026-08-01T16:00:00Z"), tokyo, now)!!
        assertEquals(utc("2026-08-01T16:00:00Z"), date.epochMillis)
        assertEquals(2 * 3600, date.utcOffsetSeconds)
    }

    @Test
    fun insta360LocalTimeStoredAsUtcFallsBackToFileName() {
        val date = CaptureDateResolver.resolve(CameraKind.INSTA360, "VID_20260505_114420_009.mp4", utc("2026-05-05T11:44:20Z"), tokyo, now)!!
        assertEquals(utc("2026-05-05T02:44:20Z"), date.epochMillis)
        assertEquals(DateSource.FILENAME, date.source)
    }

    @Test
    fun djiSameDigitsMeansCameraClockInUtc() {
        val date = CaptureDateResolver.resolve(CameraKind.DJI, "DJI_20261107124028_0007_D.MP4", utc("2026-11-07T12:40:28Z"), tokyo, now)!!
        assertEquals(utc("2026-11-07T12:40:28Z"), date.epochMillis)
        assertEquals(DateSource.CONTAINER, date.source)
    }

    @Test
    fun bogusContainerTimesLoseToTheFileName() {
        for (bogus in listOf(null, 0L, utc("1970-01-01T00:00:10Z"), utc("2015-01-01T00:00:00Z"), utc("2031-01-01T00:00:00Z"), utc("2026-05-05T03:10:07Z"))) {
            val date = CaptureDateResolver.resolve(CameraKind.INSTA360, "VID_20260505_114420_009.mp4", bogus, tokyo, now)!!
            assertEquals("container=$bogus", utc("2026-05-05T02:44:20Z"), date.epochMillis)
            assertEquals(DateSource.FILENAME, date.source)
            assertEquals(9 * 3600, date.utcOffsetSeconds)
        }
    }

    @Test
    fun containerAloneAndNothingAtAll() {
        val date = CaptureDateResolver.resolve(CameraKind.UNKNOWN, "holiday.mp4", utc("2026-05-05T02:44:20Z"), tokyo, now)!!
        assertEquals(DateSource.CONTAINER, date.source)
        assertEquals(9 * 3600, date.utcOffsetSeconds)
        assertNull(CaptureDateResolver.resolve(CameraKind.UNKNOWN, "holiday.mp4", null, tokyo, now))
        assertNull(CaptureDateResolver.resolve(CameraKind.UNKNOWN, "holiday.mp4", 0L, tokyo, now))
    }

    @Test
    fun fileNameParsing() {
        assertEquals(utc("2026-11-07T03:40:28Z"), CaptureDateResolver.fromFileName("DJI_20261107124028_0007_D.MP4", tokyo))
        assertEquals(utc("2026-05-05T02:44:20Z"), CaptureDateResolver.fromFileName("VID_20260505_114420_009.mp4", tokyo))
        assertNull(CaptureDateResolver.fromFileName("VID_20261345_996199_009.mp4", tokyo))
        assertNull(CaptureDateResolver.fromFileName("IMG_1234.JPG", tokyo))
    }
}
