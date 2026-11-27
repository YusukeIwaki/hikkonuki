package app.hikkonuki.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class YuvToRgbTest {
    private fun assertRgb(expected: FloatArray, actual: FloatArray) = assertArrayEquals(expected, actual, 0.004f)

    @Test
    fun limitedRangeEightBit() {
        val c = YuvToRgb(fullRange = false, bt601 = false, tenBit = false)
        assertRgb(floatArrayOf(0f, 0f, 0f), c.convert(16 / 255f, 128 / 255f, 128 / 255f))
        assertRgb(floatArrayOf(1f, 1f, 1f), c.convert(235 / 255f, 128 / 255f, 128 / 255f))
        // BT.709 primaries in studio range: red = (63, 102, 240), blue = (32, 240, 118)
        assertRgb(floatArrayOf(1f, 0f, 0f), c.convert(63 / 255f, 102 / 255f, 240 / 255f))
        assertRgb(floatArrayOf(0f, 0f, 1f), c.convert(32 / 255f, 240 / 255f, 118 / 255f))
    }

    @Test
    fun fullRangeKeepsItsLevels() {
        val c = YuvToRgb(fullRange = true, bt601 = false, tenBit = false)
        assertRgb(floatArrayOf(0f, 0f, 0f), c.convert(0f, 128 / 255f, 128 / 255f))
        assertRgb(floatArrayOf(1f, 1f, 1f), c.convert(1f, 128 / 255f, 128 / 255f))
        // A dark grey stays the same dark grey: this is what goes wrong when full-range
        // video is expanded as if it were limited range (it would come out as 0.007).
        assertRgb(floatArrayOf(0.069f, 0.069f, 0.069f), c.convert(0.069f, 128 / 255f, 128 / 255f))
        assertRgb(floatArrayOf(1f, 0f, 0f), c.convert(54 / 255f, 99 / 255f, 1f))
    }

    @Test
    fun limitedRangeTenBit() {
        val c = YuvToRgb(fullRange = false, bt601 = false, tenBit = true)
        assertRgb(floatArrayOf(0f, 0f, 0f), c.convert(64 / 1023f, 512 / 1023f, 512 / 1023f))
        assertRgb(floatArrayOf(1f, 1f, 1f), c.convert(940 / 1023f, 512 / 1023f, 512 / 1023f))
        assertRgb(floatArrayOf(0f, 1f, 0f), c.convert(691 / 1023f, 167 / 1023f, 105 / 1023f))
    }

    @Test
    fun bt601Primaries() {
        val c = YuvToRgb(fullRange = false, bt601 = true, tenBit = false)
        assertRgb(floatArrayOf(1f, 0f, 0f), c.convert(81 / 255f, 90 / 255f, 240 / 255f))
        assertRgb(floatArrayOf(0f, 1f, 0f), c.convert(145 / 255f, 54 / 255f, 34 / 255f))
    }
}
