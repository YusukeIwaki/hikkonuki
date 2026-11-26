package app.hikkonuki.android.core

/**
 * Coefficients for turning a decoder's Y'CbCr samples into R'G'B' on the GPU:
 * `rgb = matrix * (yuv - offset)`, with samples normalised to 0..1.
 *
 * Doing this explicitly matters for the Insta360, which records full-range video.
 * Left to the platform, a full-range picture can be expanded as if it were limited
 * range, which crushes shadows and raises contrast.
 */
class YuvToRgb(val fullRange: Boolean, val bt601: Boolean, val tenBit: Boolean) {
    /** Column-major 3x3, as GLSL's mat3 expects. */
    val matrix: FloatArray
    val offset: FloatArray

    init {
        val max = if (tenBit) 1023.0 else 255.0
        val unit = if (tenBit) 4.0 else 1.0 // 10-bit code values are the 8-bit ones times four
        val lumaScale = if (fullRange) 1.0 else max / (219 * unit)
        val chromaScale = if (fullRange) 1.0 else max / (224 * unit)
        val lumaOffset = if (fullRange) 0.0 else 16 * unit / max
        val chromaOffset = if (fullRange) 0.5 else 128 * unit / max
        val (kr, kb) = if (bt601) 0.299 to 0.114 else 0.2126 to 0.0722
        val kg = 1 - kr - kb
        val crToR = 2 * (1 - kr)
        val cbToB = 2 * (1 - kb)
        val cbToG = -cbToB * kb / kg
        val crToG = -crToR * kr / kg
        matrix = doubleArrayOf(
            lumaScale, lumaScale, lumaScale,                                     // Y column
            0.0, cbToG * chromaScale, cbToB * chromaScale,                       // Cb column
            crToR * chromaScale, crToG * chromaScale, 0.0,                       // Cr column
        ).map(Double::toFloat).toFloatArray()
        offset = floatArrayOf(lumaOffset.toFloat(), chromaOffset.toFloat(), chromaOffset.toFloat())
    }

    /** CPU reference of what the shader computes; inputs and outputs are 0..1. */
    fun convert(y: Float, cb: Float, cr: Float): FloatArray {
        val v = floatArrayOf(y - offset[0], cb - offset[1], cr - offset[2])
        return FloatArray(3) { row -> (matrix[row] * v[0] + matrix[3 + row] * v[1] + matrix[6 + row] * v[2]).coerceIn(0f, 1f) }
    }
}
