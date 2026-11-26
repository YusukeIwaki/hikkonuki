package app.hikkonuki.android.core

/** Reads the profile out of HEVC codec-specific data (Annex B: start-code separated NAL units). */
object HevcConfig {
    const val PROFILE_MAIN_10 = 2

    /** general_profile_idc of the first SPS, or null when there is none. */
    fun profileIdc(csd: ByteArray): Int? {
        var i = 0
        while (i + 3 < csd.size) {
            val startCode = csd[i].toInt() == 0 && csd[i + 1].toInt() == 0 &&
                (csd[i + 2].toInt() == 1 || (csd[i + 2].toInt() == 0 && csd[i + 3].toInt() == 1))
            if (!startCode) {
                i++
                continue
            }
            val nal = i + if (csd[i + 2].toInt() == 1) 3 else 4
            if (nal + 3 < csd.size && (csd[nal].toInt() shr 1) and 0x3F == 33) {
                // 2-byte NAL header, 1 byte of ids/flags, then profile_space(2) tier(1) profile_idc(5).
                return csd[nal + 3].toInt() and 0x1F
            }
            i = nal
        }
        return null
    }
}
