package app.hikkonuki.android.platform

import android.opengl.GLES11Ext
import android.opengl.GLES30
import app.hikkonuki.android.core.YuvToRgb
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws decoder frames (an external texture) to the current EGL surface on the GPU.
 *
 * Downscaling uses a separable Lanczos-3 filter, like ffmpeg's `scale=...:flags=lanczos`:
 * a horizontal pass into a 10-bit intermediate texture, then a vertical
 * pass to the encoder. Plain bilinear sampling at 4K -> 1440p skips source pixels and
 * aliases fine detail. When no scaling is needed the frame is copied in one pass.
 */
class FrameScaler(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val targetWidth: Int,
    private val targetHeight: Int,
) {
    val externalTexture: Int

    /**
     * True when the GPU can hand the shader the decoder's raw Y'CbCr samples
     * (GL_EXT_YUV_target), so the colour conversion is ours ([sourceColor]) rather than
     * whatever the driver assumes. Without it the driver converts implicitly.
     */
    val convertsColorExplicitly: Boolean =
        GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().split(' ').contains("GL_EXT_YUV_target")

    /** How the source encodes colour; set once the decoder has reported its format. */
    var sourceColor = YuvToRgb(fullRange = false, bt601 = false, tenBit = false)

    private val scaling = sourceWidth != targetWidth || sourceHeight != targetHeight
    private val copyProgram: Int
    private var horizontalProgram = 0
    private var verticalProgram = 0
    private var framebuffer = 0
    private var intermediateTexture = 0
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    init {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        externalTexture = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture)
        setSampling(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        copyProgram = program(VERTEX, copyFragment(convertsColorExplicitly))
        if (scaling) {
            horizontalProgram = program(VERTEX, lanczosFragment(external = true, yuv = convertsColorExplicitly))
            verticalProgram = program(VERTEX, lanczosFragment(external = false, yuv = false))
            GLES30.glGenTextures(1, textures, 0)
            intermediateTexture = textures[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, intermediateTexture)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB10_A2, targetWidth, sourceHeight, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, null,
            )
            setSampling(GLES30.GL_TEXTURE_2D)
            val buffers = IntArray(1)
            GLES30.glGenFramebuffers(1, buffers, 0)
            framebuffer = buffers[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, intermediateTexture, 0)
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "intermediate framebuffer incomplete: $status" }
        }
        checkGl("setup")
    }

    private fun setSampling(target: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /** @param textureMatrix from SurfaceTexture.getTransformMatrix (crop and orientation). */
    fun draw(textureMatrix: FloatArray) {
        if (!scaling) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, targetWidth, targetHeight)
            use(copyProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture, textureMatrix)
            drawQuad(copyProgram)
            return
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, targetWidth, sourceHeight)
        use(horizontalProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture, textureMatrix)
        setKernel(horizontalProgram, 1f, 0f, sourceWidth, sourceWidth.toFloat() / targetWidth)
        drawQuad(horizontalProgram)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, targetWidth, targetHeight)
        use(verticalProgram, GLES30.GL_TEXTURE_2D, intermediateTexture, IDENTITY)
        setKernel(verticalProgram, 0f, 1f, sourceHeight, sourceHeight.toFloat() / targetHeight)
        drawQuad(verticalProgram)
    }

    private fun use(program: Int, target: Int, texture: Int, matrix: FloatArray) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(target, texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uTexMatrix"), 1, false, matrix, 0)
        if (convertsColorExplicitly && target == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
            GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program, "uYuvToRgb"), 1, false, sourceColor.matrix, 0)
            GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uYuvOffset"), 1, sourceColor.offset, 0)
        }
    }

    private fun setKernel(program: Int, dx: Float, dy: Float, sourceLength: Int, scale: Float) {
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uDirection"), dx, dy)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uSourceLength"), sourceLength.toFloat())
        // Widen the kernel by the shrink factor so it low-pass filters; never narrower than 1.
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uScale"), scale.coerceIn(1f, MAX_RADIUS / 3f))
    }

    private fun drawQuad(program: Int) {
        val position = GLES30.glGetAttribLocation(program, "aPosition")
        GLES30.glEnableVertexAttribArray(position)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(position)
    }

    fun release() {
        GLES30.glDeleteTextures(2, intArrayOf(externalTexture, intermediateTexture), 0)
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        for (program in intArrayOf(copyProgram, horizontalProgram, verticalProgram)) {
            if (program != 0) GLES30.glDeleteProgram(program)
        }
    }

    private fun program(vertex: String, fragment: String): Int {
        val program = GLES30.glCreateProgram()
        val shaders = listOf(GLES30.GL_VERTEX_SHADER to vertex, GLES30.GL_FRAGMENT_SHADER to fragment).map { (type, text) ->
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, text)
            GLES30.glCompileShader(shader)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
            GLES30.glAttachShader(program, shader)
            shader
        }
        GLES30.glLinkProgram(program)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program link failed: ${GLES30.glGetProgramInfoLog(program)}" }
        shaders.forEach(GLES30::glDeleteShader)
        return program
    }

    private fun checkGl(stage: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(error)} during $stage" }
    }

    companion object {
        private const val MAX_RADIUS = 12
        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        private const val VERTEX = """#version 300 es
in vec2 aPosition;
out vec2 vUv;
void main() {
    vUv = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

        /** Declarations and a `fetch(uv)` returning R'G'B' for the source texture. */
        private fun sampler(external: Boolean, yuv: Boolean): String = when {
            yuv -> """#extension GL_OES_EGL_image_external_essl3 : require
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTexture;
uniform mat3 uYuvToRgb;
uniform vec3 uYuvOffset;
vec3 fetch(vec2 uv) { return clamp(uYuvToRgb * (texture(uTexture, uv).xyz - uYuvOffset), 0.0, 1.0); }
"""
            external -> """#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTexture;
vec3 fetch(vec2 uv) { return texture(uTexture, uv).rgb; }
"""
            else -> """precision highp float;
uniform sampler2D uTexture;
vec3 fetch(vec2 uv) { return texture(uTexture, uv).rgb; }
"""
        }

        private fun copyFragment(yuv: Boolean): String = """#version 300 es
${sampler(external = true, yuv = yuv)}
uniform mat4 uTexMatrix;
in vec2 vUv;
out vec4 outColor;
void main() {
    outColor = vec4(fetch((uTexMatrix * vec4(vUv, 0.0, 1.0)).xy), 1.0);
}
"""

        private fun lanczosFragment(external: Boolean, yuv: Boolean): String = """#version 300 es
${sampler(external, yuv)}
uniform mat4 uTexMatrix;
uniform vec2 uDirection;
uniform float uSourceLength;
uniform float uScale;
in vec2 vUv;
out vec4 outColor;

float lanczos3(float x) {
    x = abs(x);
    if (x < 0.0001) return 1.0;
    if (x >= 3.0) return 0.0;
    float px = 3.14159265358979 * x;
    return 3.0 * sin(px) * sin(px / 3.0) / (px * px);
}

void main() {
    // Position along the filtered axis, in source texel units (texel i covers [i, i+1)).
    float center = dot(vUv, uDirection) * uSourceLength - 0.5;
    float first = floor(center);
    float support = 3.0 * uScale;
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    for (int i = -$MAX_RADIUS; i <= $MAX_RADIUS; i++) {
        float texel = first + float(i);
        float distance = texel - center;
        if (abs(distance) >= support) continue;
        float weight = lanczos3(distance / uScale);
        float along = clamp((texel + 0.5) / uSourceLength, 0.5 / uSourceLength, 1.0 - 0.5 / uSourceLength);
        vec2 uv = vUv * (vec2(1.0) - uDirection) + uDirection * along;
        sum += weight * fetch((uTexMatrix * vec4(uv, 0.0, 1.0)).xy);
        weightSum += weight;
    }
    outColor = vec4(sum / weightSum, 1.0);
}
"""
    }
}
