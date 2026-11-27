package app.hikkonuki.android.platform

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import app.hikkonuki.android.core.TranscodeSpec
import app.hikkonuki.android.core.YuvToRgb
import app.hikkonuki.android.core.VideoTranscoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * The main conversion path: hardware decode -> GPU scale -> hardware HEVC encode, with the
 * frames never leaving GPU memory, and the audio track copied bit-for-bit.
 *
 * Compared with Media3's Transformer (kept as a fallback) this preserves 10-bit sources
 * as HEVC Main10, scales with Lanczos instead of bilinear, and does not re-encode audio:
 * the three places where the phone output used to fall short of an ffmpeg conversion on a Mac.
 *
 * @param tenBit encode Main10 through a 10-bit EGL surface. Requires a 10-bit source;
 *   construct a second instance with false as the fallback for encoders that refuse it.
 */
class DirectVideoTranscoder(private val source: AndroidSource, private val tenBit: Boolean) : VideoTranscoder {
    override val name: String = if (tenBit) "direct-10bit" else "direct-8bit"

    override suspend fun transcode(sourceId: String, spec: TranscodeSpec, output: File, onProgress: (Float) -> Unit): String {
        if (tenBit && !spec.tenBit) throw UnsupportedOperationException("8-bit source")
        // Everything touching the EGL context must stay on one thread.
        val executor = Executors.newSingleThreadExecutor { Thread(it, "hikkonuki-transcode") }
        try {
            return withContext(executor.asCoroutineDispatcher()) {
                Session(sourceId, spec, output, onProgress) { isActive }.run()
            }
        } finally {
            executor.shutdown()
        }
    }

    private inner class Session(
        private val sourceId: String,
        private val spec: TranscodeSpec,
        private val output: File,
        private val onProgress: (Float) -> Unit,
        private val isActive: () -> Boolean,
    ) {
        private val cleanup = ArrayDeque<() -> Unit>()
        private val encoderError = AtomicReference<Throwable?>()
        private val encoderDone = CountDownLatch(1)
        private val frameLock = Object()
        private var frameAvailable = false

        private fun checkAlive() {
            if (!isActive()) throw CancellationException("transcode cancelled")
            encoderError.get()?.let { throw IllegalStateException("encoder failed: ${it.message}", it) }
        }

        fun run(): String {
            var finished = false
            try {
                val description = transcode()
                finished = true
                return description
            } finally {
                while (cleanup.isNotEmpty()) runCatching { cleanup.removeLast().invoke() }
                if (!finished) output.delete()
            }
        }

        private fun transcode(): String {
            val videoExtractor = MediaExtractor().also { extractor -> cleanup += { extractor.release() } }
            source.setDataSource(videoExtractor, sourceId)
            val videoTrack = (0 until videoExtractor.trackCount).firstOrNull { index ->
                val mime = videoExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("video/") && mime != "video/mjpeg"
            } ?: throw IllegalStateException("no video track")
            videoExtractor.selectTrack(videoTrack)
            val inputFormat = videoExtractor.getTrackFormat(videoTrack)
            val sourceWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val sourceHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)

            val audioExtractor = MediaExtractor().also { extractor -> cleanup += { extractor.release() } }
            source.setDataSource(audioExtractor, sourceId)
            val audioTrack = (0 until audioExtractor.trackCount).firstOrNull { index ->
                audioExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            }
            val audioFormat = audioTrack?.let {
                audioExtractor.selectTrack(it)
                audioExtractor.getTrackFormat(it)
            }

            val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            cleanup += { muxer.release() }
            // Rotation stays a container flag, exactly as the camera recorded it.
            muxer.setOrientationHint(spec.rotationDegrees)

            val (encoder, encoderMime) = createEncoder()
            cleanup += { encoder.release() }
            val encoderThread = HandlerThread("hikkonuki-encoder").apply { start() }
            cleanup += { encoderThread.quitSafely() }
            val writer = MuxerWriter(muxer, audioExtractor.takeIf { audioFormat != null }, audioFormat)
            encoder.setCallback(writer, Handler(encoderThread.looper))
            encoder.configure(encoderFormat(encoderMime), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = encoder.createInputSurface()
            cleanup += { encoderSurface.release() }
            encoder.start()
            cleanup += { runCatching { encoder.stop() } }

            val egl = EglOutput(encoderSurface, tenBit)
            cleanup += { egl.release() }
            val scaler = FrameScaler(sourceWidth, sourceHeight, spec.width, spec.height)
            cleanup += { scaler.release() }
            scaler.sourceColor = sourceColor(inputFormat, inputFormat)
            Log.i(TAG, "explicit colour conversion: ${scaler.convertsColorExplicitly}")

            val frameThread = HandlerThread("hikkonuki-frames").apply { start() }
            cleanup += { frameThread.quitSafely() }
            val surfaceTexture = SurfaceTexture(scaler.externalTexture)
            cleanup += { surfaceTexture.release() }
            surfaceTexture.setOnFrameAvailableListener({
                synchronized(frameLock) {
                    frameAvailable = true
                    frameLock.notifyAll()
                }
            }, Handler(frameThread.looper))
            val decoderSurface = Surface(surfaceTexture)
            cleanup += { decoderSurface.release() }

            // The decoder would otherwise rotate frames on the surface; rotation is kept as
            // a container flag instead, so frames pass through in their coded orientation.
            inputFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            val decoder = createDecoder(inputFormat)
            cleanup += { decoder.release() }
            decoder.configure(inputFormat, decoderSurface, null, 0)
            decoder.start()
            cleanup += { runCatching { decoder.stop() } }

            val info = MediaCodec.BufferInfo()
            val matrix = FloatArray(16)
            var inputDone = false
            var decoderDone = false
            while (!decoderDone) {
                checkAlive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val size = videoExtractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, videoExtractor.sampleTime, 0)
                            videoExtractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    scaler.sourceColor = sourceColor(decoder.outputFormat, inputFormat)
                } else if (index >= 0) {
                    val render = info.size > 0
                    decoder.releaseOutputBuffer(index, render)
                    if (render) {
                        awaitFrame()
                        surfaceTexture.updateTexImage()
                        surfaceTexture.getTransformMatrix(matrix)
                        scaler.draw(matrix)
                        // Blocks while the encoder is busy, which paces the decoder to it.
                        egl.present(info.presentationTimeUs * 1000)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                }
            }
            encoder.signalEndOfInputStream()
            while (!encoderDone.await(100, TimeUnit.MILLISECONDS)) checkAlive()
            checkAlive()
            muxer.stop()
            val bits = if (tenBit) "10bit" else "8bit"
            val codec = if (encoderMime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "H.264"
            return String.format(Locale.US, "%dx%d %s %s %.0fMbps", spec.width, spec.height, codec, bits, spec.bitrateBps / 1e6)
        }

        /** Range and matrix as the decoder reports them, else as the container declares. */
        private fun sourceColor(primary: MediaFormat, fallback: MediaFormat): YuvToRgb {
            fun value(key: String) = primary.int(key).takeIf { it != 0 } ?: fallback.int(key)
            val standard = value(MediaFormat.KEY_COLOR_STANDARD)
            return YuvToRgb(
                fullRange = value(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL,
                bt601 = standard == MediaFormat.COLOR_STANDARD_BT601_PAL || standard == MediaFormat.COLOR_STANDARD_BT601_NTSC,
                tenBit = spec.tenBit,
            ).also { Log.i(TAG, "source colour: fullRange=${it.fullRange} bt601=${it.bt601} tenBit=${it.tenBit}") }
        }

        private fun awaitFrame() {
            val deadline = System.nanoTime() + FRAME_TIMEOUT_NANOS
            synchronized(frameLock) {
                while (!frameAvailable) {
                    frameLock.wait(100)
                    checkAlive()
                    if (System.nanoTime() > deadline) throw IllegalStateException("decoder produced no frame")
                }
                frameAvailable = false
            }
        }

        /** Prefers a decoder that declares support for this profile and size (10-bit in particular). */
        private fun createDecoder(format: MediaFormat): MediaCodec {
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val query = MediaFormat.createVideoFormat(mime, format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
            if (format.containsKey(MediaFormat.KEY_PROFILE)) query.setInteger(MediaFormat.KEY_PROFILE, format.getInteger(MediaFormat.KEY_PROFILE))
            val name = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(query) }.getOrNull()
            Log.i(TAG, "decoder ${name ?: "default for $mime"}")
            return if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
        }

        private fun createEncoder(): Pair<MediaCodec, String> {
            val mimes = if (tenBit) listOf(MediaFormat.MIMETYPE_VIDEO_HEVC) else listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
            for (mime in mimes) {
                val candidates = codecs
                    .filter { codec -> codec.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                    .sortedByDescending { it.isHardwareAccelerated }
                for (codec in candidates) {
                    val capabilities = runCatching { codec.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                    if (capabilities.videoCapabilities?.isSizeSupported(spec.width, spec.height) != true) continue
                    if (tenBit && capabilities.profileLevels.none { it.profile == CodecProfileLevel.HEVCProfileMain10 }) continue
                    Log.i(TAG, "encoder ${codec.name} for ${spec.width}x${spec.height} tenBit=$tenBit")
                    return MediaCodec.createByCodecName(codec.name) to mime
                }
            }
            throw UnsupportedOperationException("no encoder for ${spec.width}x${spec.height} tenBit=$tenBit")
        }

        private fun encoderFormat(mime: String): MediaFormat =
            MediaFormat.createVideoFormat(mime, spec.width, spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrateBps)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps.roundToInt().coerceAtLeast(1))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // Not a live stream: let the encoder favour quality over latency.
                setInteger(MediaFormat.KEY_PRIORITY, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                    setInteger(MediaFormat.KEY_PROFILE, if (tenBit) CodecProfileLevel.HEVCProfileMain10 else CodecProfileLevel.HEVCProfileMain)
                }
            }

        /**
         * Runs on the encoder's callback thread and is the only writer to the muxer.
         * Audio samples are copied from the source as the video catches up to them, which
         * keeps the two tracks interleaved in the file.
         */
        private inner class MuxerWriter(
            private val muxer: MediaMuxer,
            private val audioExtractor: MediaExtractor?,
            private val audioFormat: MediaFormat?,
        ) : MediaCodec.Callback() {
            private var videoTrack = -1
            private var audioTrack = -1
            private var audioDone = audioExtractor == null
            private val audioBuffer = ByteBuffer.allocateDirect(1 shl 20)
            private val audioInfo = MediaCodec.BufferInfo()

            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = guarded {
                check(videoTrack < 0) { "encoder changed format mid-stream" }
                videoTrack = muxer.addTrack(format)
                if (audioFormat != null) audioTrack = muxer.addTrack(audioFormat)
                muxer.start()
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = guarded {
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!config && info.size > 0) {
                    muxer.writeSampleData(videoTrack, codec.getOutputBuffer(index)!!, info)
                    copyAudioUntil(info.presentationTimeUs)
                    if (spec.durationMillis > 0) onProgress((info.presentationTimeUs / 1000f / spec.durationMillis).coerceIn(0f, 1f))
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    copyAudioUntil(Long.MAX_VALUE)
                    encoderDone.countDown()
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                encoderError.compareAndSet(null, e)
                encoderDone.countDown()
            }

            private fun copyAudioUntil(timeUs: Long) {
                val extractor = audioExtractor ?: return
                while (!audioDone) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime > timeUs) return
                    val size = extractor.readSampleData(audioBuffer, 0)
                    if (size < 0) {
                        audioDone = true
                        return
                    }
                    if (sampleTime >= 0) {
                        val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        audioInfo.set(0, size, sampleTime, flags)
                        muxer.writeSampleData(audioTrack, audioBuffer, audioInfo)
                    }
                    if (!extractor.advance()) audioDone = true
                }
            }

            private inline fun guarded(block: () -> Unit) {
                if (encoderError.get() != null) return
                try {
                    block()
                } catch (e: Throwable) {
                    encoderError.compareAndSet(null, e)
                    encoderDone.countDown()
                }
            }
        }
    }

    /** An EGL context whose window surface is the encoder's input. */
    private class EglOutput(surface: Surface, tenBit: Boolean) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val eglSurface: EGLSurface

        init {
            check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val colorBits = if (tenBit) 10 else 8
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, colorBits,
                EGL14.EGL_GREEN_SIZE, colorBits,
                EGL14.EGL_BLUE_SIZE, colorBits,
                EGL14.EGL_ALPHA_SIZE, if (tenBit) 2 else 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(16)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0)) { "eglChooseConfig failed" }
            // eglChooseConfig may return deeper formats first; insist on the exact depth.
            val config = configs.take(count[0]).filterNotNull().firstOrNull { candidate ->
                val red = IntArray(1)
                EGL14.eglGetConfigAttrib(display, candidate, EGL14.EGL_RED_SIZE, red, 0)
                red[0] == colorBits
            } ?: throw UnsupportedOperationException("no $colorBits-bit recordable EGL config")
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
        }

        fun present(timeNanos: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, timeNanos)
            check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
        }
    }

    private companion object {
        const val TAG = "Hikkonuki"
        const val DEQUEUE_TIMEOUT_US = 5_000L
        const val FRAME_TIMEOUT_NANOS = 10_000_000_000L
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
