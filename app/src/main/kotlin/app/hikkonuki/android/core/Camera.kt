package app.hikkonuki.android.core

/** Which camera wrote a file. Decides date policy, bitrate tuning and the make/model tags. */
enum class CameraKind(val make: String?) {
    DJI("DJI"),
    INSTA360("Insta360"),
    UNKNOWN(null),
}

/** The name shown for an attached camera, from what it reports about itself over USB. */
object DeviceName {
    /**
     * The product name alone. The manufacturer string is the legal entity, not the brand
     * (an Insta360 reports "Arashi Vision"), so putting it in front only pushes the name
     * people know off the end of the title. It is the fallback when there is no product
     * name. Null when the device says nothing useful.
     */
    fun of(manufacturer: String?, product: String?): String? {
        fun clean(text: String?) = text?.replace('_', ' ')?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
        return clean(product) ?: clean(manufacturer)
    }
}

object CameraDetector {
    // DJI_20261107113403_0005_D.MP4
    private val dji = Regex("""^DJI_\d{14}_\d+(_[A-Za-z0-9]+)?\.[A-Za-z0-9]+$""")

    // VID_20260505_114420_009.mp4 / VID_20260322_095529_00_001.mp4 / IMG_20260505_114420_00_012.jpg
    private val insta360 = Regex("""^(PRO_)?(VID|IMG|LRV)_\d{8}_\d{6}(_\d+)+\.[A-Za-z0-9]+$""")

    fun fromFileName(name: String): CameraKind = when {
        dji.matches(name) -> CameraKind.DJI
        insta360.matches(name) -> CameraKind.INSTA360
        else -> CameraKind.UNKNOWN
    }

    /**
     * "DJI OsmoPocket4P" (the container's encoder tag) -> "OsmoPocket4P".
     * The model is only ever taken from the file itself, never invented from the file name.
     */
    fun djiModelFromEncoderTag(encoder: String?): String? =
        encoder?.trim()?.takeIf { it.startsWith("DJI ") }?.removePrefix("DJI ")?.trim()?.takeIf { it.isNotEmpty() }

    private val insta360Model = Regex("""Insta360(?: [A-Za-z0-9.+_-]+){1,5}""")

    /**
     * Insta360 files carry the model as plain text in their trailing `inst` box
     * ("Insta360 Ace Pro 2"). [tail] is the last chunk of the file.
     */
    fun insta360ModelFromTail(tail: ByteArray): String? {
        val text = String(tail, Charsets.ISO_8859_1)
        return insta360Model.findAll(text).map { it.value.trim() }.maxByOrNull { it.length }
    }
}
