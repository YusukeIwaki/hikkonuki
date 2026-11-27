package app.hikkonuki.android.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

enum class DateSource { CONTAINER, FILENAME }

/**
 * @param utcOffsetSeconds the camera clock's offset from UTC at capture time, when it could
 *   be determined. Written next to the UTC instant so the local capture time survives.
 */
data class CaptureDate(val epochMillis: Long, val source: DateSource, val utcOffsetSeconds: Int)

/**
 * Decides the capture instant of a video from the two things a camera leaves behind:
 * the container's creation time (UTC) and the wall-clock timestamp in the file name.
 *
 * Neither is trustworthy alone. The container time can be unset (1904/1970), or written
 * as local time mislabelled as UTC; the file name has no time zone. Used together:
 *
 * - they differ by a plausible UTC offset -> the container is real UTC, and the
 *   difference is the camera's time zone (correct even when the phone has since moved);
 * - they show the same digits -> ambiguous (camera in UTC, or local time stored as UTC).
 *   DJI is known to write real UTC, so its container wins; otherwise the file name is
 *   read in the phone's zone;
 * - anything else -> the container is bogus and the file name wins.
 */
object CaptureDateResolver {
    private val filenamePattern = Regex("""(?<!\d)(\d{8})_?(\d{6})(?!\d)""")
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private const val TOLERANCE_SECONDS = 2L
    private const val MIN_VALID_MILLIS = 315_532_800_000L // 1980-01-01, QuickTime "unset" dates resolve earlier

    fun localDateTimeFromFileName(name: String): LocalDateTime? {
        val match = filenamePattern.find(name) ?: return null
        return runCatching { LocalDateTime.parse(match.groupValues[1] + match.groupValues[2], formatter) }.getOrNull()
    }

    /** File-name timestamp read as wall-clock time in [zone]. Cheap enough for list screens. */
    fun fromFileName(name: String, zone: ZoneId): Long? =
        localDateTimeFromFileName(name)?.atZone(zone)?.toInstant()?.toEpochMilli()

    fun isValidContainerTime(epochMillis: Long?, nowMillis: Long): Boolean =
        epochMillis != null && epochMillis >= MIN_VALID_MILLIS && epochMillis <= nowMillis + 86_400_000L

    fun resolve(
        camera: CameraKind,
        fileName: String,
        containerMillis: Long?,
        zone: ZoneId,
        nowMillis: Long,
    ): CaptureDate? {
        val container = containerMillis?.takeIf { isValidContainerTime(it, nowMillis) }
        val local = localDateTimeFromFileName(fileName)
        fun fromName(): CaptureDate? = local?.let {
            val zoned = it.atZone(zone)
            CaptureDate(zoned.toInstant().toEpochMilli(), DateSource.FILENAME, zoned.offset.totalSeconds)
        }
        fun fromContainer(offsetSeconds: Int? = null): CaptureDate? = container?.let {
            val offset = offsetSeconds ?: zone.rules.getOffset(java.time.Instant.ofEpochMilli(it)).totalSeconds
            CaptureDate(it, DateSource.CONTAINER, offset)
        }
        if (container == null) return fromName()
        if (local == null) return fromContainer()

        // How far the file name's wall clock is ahead of the container's UTC instant.
        val offsetSeconds = local.toEpochSecond(ZoneOffset.UTC) - container / 1000
        if (abs(offsetSeconds) <= TOLERANCE_SECONDS) {
            return if (camera == CameraKind.DJI) fromContainer() else fromName()
        }
        val quarterHours = Math.round(offsetSeconds / 900.0)
        val snapped = quarterHours * 900
        val plausibleZone = snapped in -12 * 3600..14 * 3600 && abs(offsetSeconds - snapped) <= TOLERANCE_SECONDS
        return if (plausibleZone) fromContainer(snapped.toInt()) else fromName()
    }
}
